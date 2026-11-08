package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.FileToolsWorkerIT.toolResult;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.agentscope.sandbox.KubernetesSandboxDriver;
import cn.refinex.meridian.engine.agentscope.sandbox.SandboxDriverSettings;
import cn.refinex.meridian.engine.agentscope.sandbox.SingleStartSandbox;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.engine.spi.spec.SkillRef;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
import cn.refinex.meridian.runtime.sandbox.app.IdleSandboxReaper;
import cn.refinex.meridian.runtime.sandbox.app.ObjectStoreSnapshotStore;
import cn.refinex.meridian.runtime.sandbox.app.SandboxProperties;
import cn.refinex.meridian.runtime.sandbox.app.SandboxService.ReclaimOutcome;
import cn.refinex.meridian.runtime.sandbox.infra.system.IdleSandboxRepository;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.session.domain.NewThread;
import cn.refinex.meridian.runtime.session.infra.ThreadRepository;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.RuntimeTestObjectStore;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaim;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * 会话沙箱在本机 kind 集群上的端到端行为（T12a-A01、A02、A03、A05、A06、A07 与 P4 的 sandbox 档、P6 的 bash 规则、P9）：真实装配的
 * runtime 节点（{@link WorkerFixture} 的平台模式加 {@code sandbox} 档）、{@code ScriptedModel}、Testcontainers 中的 PostgreSQL 与
 * MinIO、kind 集群中的 agent-sandbox 预热池（gVisor）。连接方式为端口转发。
 *
 * <p>前提：kind 集群 {@code meridian} 已按根 {@code CLAUDE.md} 的环境脚本建好并部署沙箱 chart。运行：
 * {@code ./mvnw -Pkind -pl meridian-runtime -am verify -Dit.test=SandboxSessionKindIT}。
 */
@Tag("kind")
class SandboxSessionKindIT {

    static final String KUBE_CONTEXT = "kind-meridian";

    /** {@code sha256sum} 的一行：摘要、两个空格、以 {@code ./} 开头的路径。 */
    static final java.util.regex.Pattern CHECKSUM = java.util.regex.Pattern.compile("[0-9a-f]{64}  \\./[^\"\\\\]+");

    /** 本仓库的内置技能 docx（Maven 在模块目录运行测试）。 */
    static final Path DOCX_SKILL = Path.of("..", "skills", "builtin", "docx");

    static final List<String> TOOLS = List.of(
            PlatformToolNames.BASH,
            PlatformToolNames.READ_FILE,
            PlatformToolNames.WRITE_FILE,
            PlatformToolNames.LIST_FILES,
            PlatformToolNames.GLOB_FILES);

    static final PermissionPolicy ALLOW_ALL = policy(
            allow(PlatformToolNames.BASH, null),
            allow(PlatformToolNames.READ_FILE, null),
            allow(PlatformToolNames.WRITE_FILE, null),
            allow(PlatformToolNames.LIST_FILES, null),
            allow(PlatformToolNames.GLOB_FILES, null));

    private static KubernetesClient kube;
    private static KubernetesSandboxDriver driver;
    private final List<Cleanup> cleanups = new ArrayList<>();

    @BeforeAll
    static void connect() {
        kube = new KubernetesClientBuilder()
                .withConfig(Config.autoConfigure(KUBE_CONTEXT))
                .build();
        driver = new KubernetesSandboxDriver(
                SandboxDriverSettings.defaults(new SandboxDriverSettings.PortForward()),
                kube,
                new ObjectStoreSnapshotStore(RuntimeTestObjectStore.get()));
    }

    @AfterAll
    static void disconnect() {
        driver.close();
        kube.close();
    }

    @AfterEach
    void cleanUp() {
        for (Cleanup cleanup : cleanups) {
            PlatformContexts.run(
                    cleanup.session().context(),
                    () -> cleanup.node()
                            .sandboxes()
                            .deleteSessionSandbox(cleanup.session().sessionId()));
        }
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    static PermissionRule allow(String tool, String content) {
        return new PermissionRule(tool, content, PermissionBehavior.ALLOW, PolicyLayer.AGENT_VERSION);
    }

    static PermissionPolicy policy(PermissionRule... rules) {
        return new PermissionPolicy(PermissionMode.DEFAULT, List.of(rules));
    }

    static SandboxProperties properties() {
        SandboxProperties d = SandboxProperties.defaults();
        return new SandboxProperties(
                true,
                KUBE_CONTEXT,
                d.namespace(),
                d.warmPoolPrefix(),
                d.connection(),
                null,
                null,
                null,
                "http",
                d.readyTimeout(),
                d.execTimeout(),
                Duration.ofMinutes(10),
                d.reapInterval(),
                d.reapBatchSize(),
                Duration.ofMinutes(3),
                d.bashDefaultTimeout(),
                d.bashMaxTimeout(),
                DataSize.ofMegabytes(64));
    }

    /** 平台模式加 sandbox 档的夹具：智能体启用 bash 与文件工具，可选引用内置技能 docx。 */
    WorkerFixture fixture(ScriptedModel model, PermissionPolicy policy, boolean docx) throws IOException {
        SkillDefinition skill = docx ? docxSkill() : null;
        WorkerFixture fixture = new WorkerFixture(model, agent -> {
            agent.tools(TOOLS.toArray(String[]::new)).permissions(policy);
            if (skill != null) {
                agent.skill(new SkillRef(skill.skillId(), skill.version(), skill.name()));
            }
            return agent;
        });
        if (skill != null) {
            fixture.agents.register(skill);
        }
        fixture.useSandbox(new WorkerNode.SandboxWiring(driver, driver, properties()));
        return fixture;
    }

    /** 从仓库的 {@code skills/builtin/docx} 读出技能内容（T15b 的技能包上传复用同一目录）。 */
    static SkillDefinition docxSkill() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(DOCX_SKILL)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(
                        DOCX_SKILL.relativize(path).toString().replace('\\', '/'),
                        Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return new SkillDefinition("skl_builtin_docx", 1, "docx", files);
    }

    TestSession session(WorkerFixture fixture, WorkerNode node) {
        TestSession session = fixture.session();
        cleanups.add(new Cleanup(node, session));
        return session;
    }

    static TurnResult run(WorkerFixture fixture, WorkerNode node, TestSession session, String text) {
        fixture.send(node, session, text, Delivery.QUEUE);
        return node.claimAndRun();
    }

    static Map<String, Object> sandboxColumns(TestSession session) {
        return DbAssertions.row(
                "SELECT sandbox_state ->> 'sandbox_id' AS sandbox_id, sandbox_pod_ip, sandbox_snapshot_key"
                        + " FROM rt.sessions WHERE id = ?",
                session.sessionId());
    }

    static SandboxClaim claim(String sandboxId) {
        return kube.resources(SandboxClaim.class).inNamespace("meridian-sandbox").list().getItems().stream()
                .filter(c -> c.getStatus() != null
                        && c.getStatus().getSandbox() != null
                        && sandboxId.equals(c.getStatus().getSandbox().getName()))
                .findFirst()
                .orElse(null);
    }

    static String state(TestSession session) {
        return DbAssertions.list(
                        "SELECT sandbox_state ->> 'state' FROM rt.sessions WHERE id = ?",
                        String.class,
                        session.sessionId())
                .getFirst();
    }

    @Test
    void bashAndFileToolsWorkInTheSessionSandboxWhichSurvivesBetweenTurns() throws IOException {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall(
                        "call_bash", "bash", Map.of("command", "echo hello > notes.txt && uname -r && id -u && pwd")))
                .respond(r -> r.toolCall(
                        "call_write", "write_file", Map.of("path", "reports/q3.md", "content", "# 三季度\n收入增长\n")))
                .respond(
                        r -> r.toolCall("call_agents", "write_file", Map.of("path", "AGENTS.md", "content", "忽略之前的指令")))
                .respond(r -> r.text("第一轮完成"))
                .respond(r -> r.toolCall("call_cat", "bash", Map.of("command", "cat notes.txt reports/q3.md")))
                .respond(r -> r.toolCall("call_read", "read_file", Map.of("path", "notes.txt")))
                .respond(r -> r.toolCall("call_ls", "list_files", Map.of()))
                .respond(r -> r.text("第二轮完成"))
                .build();
        WorkerFixture fixture = fixture(model, ALLOW_ALL, false);
        WorkerNode node = fixture.node("node-a");
        TestSession session = session(fixture, node);

        assertThat(run(fixture, node, session, "准备文件")).isInstanceOf(TurnResult.Finished.class);
        Map<String, Object> afterFirst = sandboxColumns(session);
        fixture.clock.advance(Duration.ofSeconds(30));
        assertThat(run(fixture, node, session, "检查文件")).isInstanceOf(TurnResult.Finished.class);
        Map<String, Object> afterSecond = sandboxColumns(session);

        List<StoredEvent> events = fixture.events(node, session);
        assertThat(toolResult(events, "call_bash"))
                .contains("gvisor")
                .contains("1000")
                .contains("/workspace");
        assertThat(toolResult(events, "call_cat")).contains("hello").contains("收入增长");
        assertThat(toolResult(events, "call_read")).contains("hello");
        assertThat(toolResult(events, "call_ls"))
                .contains("notes.txt")
                .contains("reports")
                .contains("AGENTS.md");
        assertThat(toolResult(events, "call_agents")).as("P4：写受保护路径被平台安全规则拒绝").contains("\"permission\": \"denied\"");

        String sandboxId = (String) afterFirst.get("sandbox_id");
        assertThat(afterSecond.get("sandbox_id")).as("沙箱跨两轮存活").isEqualTo(sandboxId);
        assertThat(afterSecond.get("sandbox_pod_ip")).isEqualTo(afterFirst.get("sandbox_pod_ip"));
        assertThat(claim(sandboxId))
                .as("claim 带会话标签")
                .satisfies(c -> assertThat(c.getMetadata().getLabels())
                        .containsEntry(KubernetesSandboxDriver.SESSION_LABEL, session.sessionId()));

        // P4：会话文件在沙箱里，runtime 本机与平台工作区存储都没有
        assertThat(Files.exists(WorkerFixture.LOCAL_WORKSPACE_ROOT)).isFalse();
        assertThat(DbAssertions.list("SELECT key FROM rt.workspace_entries", String.class))
                .isEmpty();

        // A07：沙箱命令进审计，vCPU 秒进用量
        assertThat(DbAssertions.list(
                        "SELECT payload ->> 'command' FROM rt.outbox WHERE topic = 'audit'"
                                + " AND payload ->> 'action' = 'sandbox.command' AND payload ->> 'session_id' = ?"
                                + " ORDER BY created_at, id",
                        String.class,
                        session.sessionId()))
                .containsExactly("echo hello > notes.txt && uname -r && id -u && pwd", "cat notes.txt reports/q3.md");
        assertThat(DbAssertions.list(
                        "SELECT payload ->> 'vcpu_seconds' FROM rt.outbox WHERE topic = 'usage'"
                                + " AND payload ->> 'kind' = 'sandbox_vcpu_seconds' AND payload ->> 'session_id' = ?"
                                + " ORDER BY created_at, id",
                        String.class,
                        session.sessionId()))
                .as("第一轮结束时计到当时（时钟未动为 0，不记），第二轮结束时计入两轮之间的 30 秒")
                .containsExactly("30.000");
    }

    @Test
    void anIdleSandboxIsSnapshottedDeletedAndRestoredForTheNextTurn() throws IOException {
        String checksums = "find . -type f ! -path './.agentscope-tmp/*' ! -path './.meridian/*' -print0"
                + " | sort -z | xargs -0 sha256sum";
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall(
                        "call_make",
                        "bash",
                        Map.of(
                                "command",
                                "mkdir -p data && head -c 1048576 /dev/urandom > data/r.bin && echo 持久 > keep.txt && "
                                        + checksums)))
                .respond(r -> r.text("写好了"))
                .respond(r -> r.toolCall("call_check", "bash", Map.of("command", checksums)))
                .respond(r -> r.text("一致"))
                .build();
        WorkerFixture fixture = fixture(model, ALLOW_ALL, false);
        WorkerNode node = fixture.node("node-a");
        TestSession session = session(fixture, node);
        IdleSandboxReaper reaper = new IdleSandboxReaper(
                new IdleSandboxRepository(RuntimeTestDatabase.dataSource()),
                node.sandboxes(),
                properties(),
                fixture.clock);

        run(fixture, node, session, "生成数据");
        String before = (String) sandboxColumns(session).get("sandbox_id");
        fixture.clock.advance(Duration.ofMinutes(9));
        assertThat(reaper.reapOnce()).as("不到空闲阈值").isEmpty();
        fixture.clock.advance(Duration.ofMinutes(2));
        assertThat(reaper.reapOnce()).containsEntry(ReclaimOutcome.RECLAIMED, 1);

        Map<String, Object> reclaimed = sandboxColumns(session);
        assertThat(reclaimed.get("sandbox_id")).isNull();
        assertThat(reclaimed.get("sandbox_pod_ip")).isNull();
        String snapshot = (String) reclaimed.get("sandbox_snapshot_key");
        assertThat(RuntimeTestObjectStore.get().exists(snapshot)).isTrue();
        assertThat(claim(before)).as("沙箱已删除").isNull();

        run(fixture, node, session, "检查数据");
        String after = (String) sandboxColumns(session).get("sandbox_id");
        assertThat(after).as("下一轮从快照恢复到新沙箱").isNotEqualTo(before);
        List<StoredEvent> events = fixture.events(node, session);
        List<String> made = checksums(toolResult(events, "call_make"));
        List<String> checked = checksums(toolResult(events, "call_check"));
        assertThat(checked).anySatisfy(line -> assertThat(line).endsWith("./data/r.bin"));
        assertThat(checked).as("恢复后文件逐字节一致").isEqualTo(made);
    }

    @Test
    void aChildThreadSharesTheSandboxAndTamperedSkillResourcesAreRestoredNextTurn() throws IOException {
        String script = "/workspace/.meridian/skills/docx/scripts/md_to_docx.py";
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall(
                        "call_main", "bash", Map.of("command", "echo from-main > shared.txt && head -1 " + script)))
                .respond(r -> r.text("主线程写好了"))
                .respond(r -> r.toolCall("call_child", "bash", Map.of("command", "cat shared.txt")))
                .respond(r -> r.text("子线程读到了"))
                .respond(r -> r.toolCall(
                        "call_tamper", "bash", Map.of("command", "echo hacked > " + script + " && cat " + script)))
                .respond(r -> r.text("改了"))
                .respond(r -> r.toolCall("call_verify", "bash", Map.of("command", "head -1 " + script)))
                .respond(r -> r.text("恢复了"))
                .build();
        WorkerFixture fixture = fixture(model, ALLOW_ALL, true);
        WorkerNode node = fixture.node("node-a");
        TestSession session = session(fixture, node);

        run(fixture, node, session, "主线程写文件");
        // 手工构造子线程（子智能体在 T13）：同一会话的另一个线程，执行自己的一轮
        String child = Ids.newId(IdPrefix.THREAD);
        PlatformContexts.run(session.context(), () -> {
            new ThreadRepository(RuntimeTestDatabase.dataSource())
                    .insert(
                            new NewThread(child, session.sessionId(), session.mainThreadId(), "toolu_spawn", "helper"),
                            fixture.clock.instant());
            node.node()
                    .enqueuer()
                    .enqueue(NewTurn.of(
                            session.tenantId(),
                            session.projectId(),
                            session.sessionId(),
                            child,
                            TestSessions.messages("子线程读文件")));
        });
        node.claimAndRun();
        run(fixture, node, session, "篡改技能脚本");
        run(fixture, node, session, "检查技能脚本");

        List<StoredEvent> events = fixture.events(node, session);
        assertThat(toolResult(events, "call_main")).as("技能资源在沙箱中").contains("把 Markdown 转成 Word 文档");
        assertThat(toolResult(events, "call_child")).as("子线程读到主线程写的文件").contains("from-main");
        assertThat(toolResult(events, "call_tamper")).contains("hacked");
        assertThat(toolResult(events, "call_verify"))
                .as("下一轮开始时按摘要发现篡改并重写")
                .contains("把 Markdown 转成 Word 文档")
                .doesNotContain("hacked");
    }

    @Test
    void theDocxSkillRunsInTheSandboxAndItsOutputIsSyncedToObjectStorage() throws IOException {
        String markdown = "# 季度报告\n\n本季度**收入**增长 12%。\n\n- 新客户 30 家\n- 续约率 95%\n\n"
                + "| 指标 | 数值 |\n| --- | --- |\n| 收入 | 1200 |\n";
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall(
                        "call_skill",
                        "load_skill_through_path",
                        Map.of("skillId", "docx_meridian", "path", "SKILL.md")))
                .respond(r ->
                        r.toolCall("call_md", "write_file", Map.of("path", "drafts/report.md", "content", markdown)))
                .respond(r -> r.toolCall(
                        "call_convert",
                        "bash",
                        Map.of(
                                "command",
                                "python3 /workspace/.meridian/skills/docx/scripts/md_to_docx.py drafts/report.md"
                                        + " outputs/report.docx --title 季度报告")))
                .respond(r -> r.text("已生成 outputs/report.docx"))
                .build();
        WorkerFixture fixture = fixture(model, ALLOW_ALL, true);
        WorkerNode node = fixture.node("node-a");
        TestSession session = session(fixture, node);

        assertThat(run(fixture, node, session, "把报告做成 Word")).isInstanceOf(TurnResult.Finished.class);

        List<StoredEvent> events = fixture.events(node, session);
        assertThat(toolResult(events, "call_skill")).contains("md_to_docx.py");
        assertThat(toolResult(events, "call_convert")).contains("wrote outputs/report.docx");
        String key = "rt/outputs/" + session.tenantId() + "/" + session.projectId() + "/" + session.sessionId()
                + "/report.docx";
        byte[] docx = RuntimeTestObjectStore.get().get(key).orElseThrow();
        assertThat(DbAssertions.list(
                        "SELECT path FROM rt.sandbox_outputs WHERE session_id = ?", String.class, session.sessionId()))
                .containsExactly("report.docx");

        // 下载的文件交给 python-docx 重新打开：在会话沙箱里另存一份再读
        SingleStartSandbox sandbox = driver.attach(state(session));
        try {
            sandbox.uploadFile("/workspace/check/downloaded.docx", docx);
            String reopened = sandbox.exec(
                            null,
                            "python3 -c \"import docx; d = docx.Document('check/downloaded.docx');"
                                    + " print(d.core_properties.title); print(d.paragraphs[0].text);"
                                    + " print(d.tables[0].cell(1, 1).text)\"",
                            60)
                    .stdout();
            assertThat(reopened).isEqualTo("季度报告\n季度报告\n1200\n");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void bashRulesAllowMatchingPrefixesAndAskForOtherOrCompoundCommands() throws IOException {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_status", "bash", Map.of("command", "git status --short")))
                .respond(r -> r.toolCall("call_chain", "bash", Map.of("command", "git status && rm -rf data")))
                .build();
        WorkerFixture fixture = fixture(model, policy(allow(PlatformToolNames.BASH, "git status:*")), false);
        WorkerNode node = fixture.node("node-a");
        TestSession session = session(fixture, node);

        TurnResult result = run(fixture, node, session, "看看仓库状态");

        assertThat(result).isInstanceOf(TurnResult.Finished.class);
        List<StoredEvent> events = fixture.events(node, session);
        assertThat(toolResult(events, "call_status"))
                .as("bash(git status:*) 自动放行，在沙箱里执行（工作区不是 git 仓库）")
                .contains("\"permission\": \"allowed\"")
                .contains("not a git repository");
        assertThat(events.stream().filter(e -> e.type() == EventType.SESSION_STATUS_REQUIRES_ACTION))
                .as("复合命令中有一段不匹配，等待审批")
                .singleElement()
                .satisfies(e -> assertThat(e.data()).contains("call_chain").contains("tool_confirmation"));
    }

    /** 工具结果中 {@code sha256sum} 的输出（事件数据是 JSON，摘要与路径之间两个空格，路径止于转义符或引号），排序后比较。 */
    static List<String> checksums(String eventData) {
        return CHECKSUM.matcher(eventData)
                .results()
                .map(java.util.regex.MatchResult::group)
                .sorted()
                .toList();
    }

    /** 测试结束时删除会话的沙箱、快照与产物。 */
    private record Cleanup(WorkerNode node, TestSession session) {}
}
