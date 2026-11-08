package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.ofType;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.RuntimeTestObjectStore;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import cn.refinex.meridian.runtime.workspace.app.DatabaseWorkspaceStore;
import cn.refinex.meridian.runtime.workspace.infra.system.WorkspaceEntryRepository;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 文件工具在完整节点上的行为（T10a）：生产的平台工具、{@code none} 档工作区（{@code DatabaseWorkspaceStore}，PostgreSQL 与
 * MinIO）、工具账本与每轮的权限合成（含 ADR-0031 的平台安全规则）。
 *
 * <ul>
 *   <li>A03（P4 {@code none} 档）：一轮读写文件之后，小文件在 {@code rt.workspace_entries} 内联、大文件进对象存储，
 *       runtime 节点本地磁盘上没有任何会话文件。
 *   <li>A04：每次调用都有账本记录，幂等键为 {@code session_id:tool_use_id}；有副作用的调用写审计。
 *   <li>A02：路径 glob 规则放行匹配路径、其他路径按默认处理（需要确认）；写受保护路径被平台安全规则拒绝；未读先改被拒绝。
 * </ul>
 */
class FileToolsWorkerIT {

    /** {@code TestSessions} 创建的会话的终端用户：命名空间的第三段取自会话（经 {@code PlatformContext.endUserId}）。 */
    static final String END_USER = "u_1024";

    /** 一次会话的文件工具都放行（DEFAULT 模式下没有命中 allow 的调用都要确认）。 */
    static final PermissionPolicy FILE_TOOLS_ALLOWED = new PermissionPolicy(
            PermissionMode.DEFAULT,
            List.of(
                    allow(PlatformToolNames.READ_FILE, null),
                    allow(PlatformToolNames.LIST_FILES, null),
                    allow(PlatformToolNames.WRITE_FILE, null),
                    allow(PlatformToolNames.EDIT_FILE, null)));

    @AfterEach
    void resetGracefulShutdown() {
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    static PermissionRule allow(String tool, String content) {
        return new PermissionRule(tool, content, PermissionBehavior.ALLOW, PolicyLayer.AGENT_VERSION);
    }

    static WorkerFixture fixture(ScriptedModel model, PermissionPolicy policy) {
        WorkerFixture fixture = new WorkerFixture(
                model,
                agent -> agent.tools(
                                PlatformToolNames.READ_FILE,
                                PlatformToolNames.LIST_FILES,
                                PlatformToolNames.WRITE_FILE,
                                PlatformToolNames.EDIT_FILE)
                        .permissions(policy));
        fixture.platform = true;
        return fixture;
    }

    static String toolResult(List<StoredEvent> events, String toolUseId) {
        return ofType(events, EventType.AGENT_TOOL_RESULT).stream()
                .map(StoredEvent::data)
                .filter(data -> data.contains("\"tool_use_id\": \"" + toolUseId + "\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有工具结果 " + toolUseId));
    }

    @Test
    void aTurnThatReadsAndWritesFilesLeavesNothingOnTheLocalDisk() throws IOException {
        Instant started = Instant.now();
        String small = "# 周报\n本周完成了文件工具。\n";
        String big = "id,value\n" + "1,abcdefghij\n".repeat(8000);
        ScriptedModel model = ScriptedModel.builder()
                .respond(r ->
                        r.toolCall("call_small", "write_file", Map.of("path", "notes/weekly.md", "content", small)))
                .respond(r -> r.toolCall("call_big", "write_file", Map.of("path", "data/big.csv", "content", big)))
                .respond(r -> r.toolCall(
                        "call_edit",
                        "edit_file",
                        Map.of("path", "notes/weekly.md", "old_string", "文件工具", "new_string", "平台文件工具")))
                .respond(r -> r.toolCall("call_read", "read_file", Map.of("path", "data/big.csv", "limit", 2)))
                .respond(r -> r.toolCall("call_ls", "list_files", Map.of("path", "data")))
                .respond(r -> r.text("都写好了"))
                .build();
        WorkerFixture fixture = fixture(model, FILE_TOOLS_ALLOWED);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.send(node, session, "整理周报和数据", Delivery.QUEUE);

        TurnResult result = node.claimAndRun();

        assertThat(result)
                .isInstanceOfSatisfying(
                        TurnResult.Finished.class, f -> assertThat(f.outcome()).isEqualTo(TurnOutcome.COMPLETED));
        List<StoredEvent> events = fixture.events(node, session);
        assertThat(toolResult(events, "call_read")).contains("1\\tid,value", "2\\t1,abcdefghij");
        assertThat(toolResult(events, "call_ls")).contains("data/big.csv");

        // 小文件内联在 rt.workspace_entries，大文件在对象存储，命名空间 [tenant, project, end_user, session]
        List<Map<String, Object>> entries = DbAssertions.rows(
                "SELECT namespace::text AS namespace, key, version, size, inline_content IS NOT NULL AS inline, object_key"
                        + " FROM rt.workspace_entries ORDER BY key");
        String namespace =
                "{" + session.tenantId() + "," + session.projectId() + "," + END_USER + "," + session.sessionId() + "}";
        assertThat(entries).extracting(e -> e.get("key")).containsExactly("/data/big.csv", "/notes/weekly.md");
        assertThat(entries).allSatisfy(e -> assertThat(e.get("namespace")).isEqualTo(namespace));
        Map<String, Object> bigEntry = entries.get(0);
        assertThat(bigEntry.get("inline")).isEqualTo(false);
        assertThat((Long) bigEntry.get("size")).isGreaterThan(DatabaseWorkspaceStore.INLINE_LIMIT_BYTES);
        String objectKey = (String) bigEntry.get("object_key");
        assertThat(objectKey).startsWith("rt/" + session.tenantId() + "/" + session.sessionId() + "/workspace/");
        assertThat(RuntimeTestObjectStore.get().get(objectKey)).isPresent();
        Map<String, Object> smallEntry = entries.get(1);
        assertThat(smallEntry.get("inline")).isEqualTo(true);
        assertThat(smallEntry.get("object_key")).isNull();
        assertThat(smallEntry.get("version")).as("创建一次、编辑一次").isEqualTo(2L);
        assertThat(DbAssertions.list(
                        "SELECT inline_content ->> 'content' FROM rt.workspace_entries WHERE key = '/notes/weekly.md'",
                        String.class))
                .containsExactly("# 周报\n本周完成了平台文件工具。\n");

        // P4：runtime 节点本地磁盘上没有任何会话文件
        assertThat(Files.exists(WorkerFixture.LOCAL_WORKSPACE_ROOT))
                .as("平台控制的本机工作区根目录没有被创建")
                .isFalse();
        assertThat(filesModifiedAfter(Path.of(System.getProperty("user.dir"), ".agentscope"), started))
                .as("harness 的默认本机工作区没有写入")
                .isEmpty();
    }

    @Test
    void everyCallIsInTheLedgerWithTheSessionToolUseKeyAndSideEffectsAreAudited() {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_w", "write_file", Map.of("path", "a.md", "content", "甲")))
                .respond(r -> r.toolCall("call_r", "read_file", Map.of("path", "a.md")))
                .respond(r -> r.toolCall("call_missing", "read_file", Map.of("path", "nope.md")))
                .respond(r -> r.text("完成"))
                .build();
        WorkerFixture fixture = fixture(model, FILE_TOOLS_ALLOWED);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.send(node, session, "写一个文件", Delivery.QUEUE);

        node.claimAndRun();

        List<Map<String, Object>> invocations = DbAssertions.rows(
                "SELECT tool_use_id, tool_name, side_effect, idempotency_key, status, thread_id"
                        + " FROM rt.tool_invocations WHERE session_id = ? ORDER BY started_at, id",
                session.sessionId());
        assertThat(invocations)
                .extracting(
                        i -> i.get("tool_use_id"),
                        i -> i.get("side_effect"),
                        i -> i.get("idempotency_key"),
                        i -> i.get("status"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "call_w", "idempotent", session.sessionId() + ":call_w", "succeeded"),
                        org.assertj.core.groups.Tuple.tuple(
                                "call_r", "none", session.sessionId() + ":call_r", "succeeded"),
                        org.assertj.core.groups.Tuple.tuple(
                                "call_missing", "none", session.sessionId() + ":call_missing", "failed"));
        assertThat(invocations).allSatisfy(i -> assertThat(i.get("thread_id")).isEqualTo(session.mainThreadId()));
        assertThat(DbAssertions.list(
                        "SELECT payload ->> 'tool_use_id' FROM rt.outbox WHERE topic = 'audit'"
                                + " AND payload ->> 'action' = 'tool.executed' AND payload ->> 'session_id' = ?",
                        String.class,
                        session.sessionId()))
                .as("只有有副作用的调用写审计")
                .containsExactly("call_w");
    }

    @Test
    void pathRulesAllowMatchingPathsDenyProtectedPathsAndAskForTheRest() {
        // 智能体放行 read_file(reports/**) 与整个 write_file；平台安全规则（合成时注入）拒绝写受保护路径
        PermissionPolicy policy = new PermissionPolicy(
                PermissionMode.DEFAULT,
                List.of(allow(PlatformToolNames.READ_FILE, "reports/**"), allow(PlatformToolNames.WRITE_FILE, null)));
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_report", "read_file", Map.of("path", "reports/q3.md")))
                .respond(r -> r.toolCall("call_agents", "write_file", Map.of("path", "AGENTS.md", "content", "新提示词")))
                .respond(r ->
                        r.toolCall("call_skill", "write_file", Map.of("path", "skills/x/SKILL.md", "content", "x")))
                .respond(r -> r.toolCall("call_secret", "read_file", Map.of("path", "private/salary.md")))
                .build();
        WorkerFixture fixture = fixture(model, policy);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        seed(session, "reports/q3.md", "三季度营收增长");
        seed(session, "private/salary.md", "工资表");
        fixture.send(node, session, "看看报告", Delivery.QUEUE);

        TurnResult result = node.claimAndRun();

        assertThat(result)
                .isInstanceOfSatisfying(
                        TurnResult.Finished.class, f -> assertThat(f.outcome()).isEqualTo(TurnOutcome.AWAITING_ACTION));
        List<StoredEvent> events = fixture.events(node, session);
        assertThat(toolResult(events, "call_report"))
                .as("匹配 reports/** 的路径直接放行")
                .contains("\"permission\": \"allowed\"", "三季度营收增长");
        assertThat(toolResult(events, "call_agents"))
                .as("写受保护路径被平台安全规则拒绝")
                .contains("\"permission\": \"denied\"", "\"state\": \"denied\"");
        assertThat(toolResult(events, "call_skill")).contains("\"permission\": \"denied\"");
        assertThat(fixture.actions(session))
                .as("不匹配规则的路径按默认处理：需要确认")
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.toolUseId()).isEqualTo("call_secret");
                    assertThat(action.type().value()).isEqualTo("tool_confirmation");
                });
        assertThat(DbAssertions.list("SELECT key FROM rt.workspace_entries ORDER BY key", String.class))
                .as("受保护路径没有落进工作区")
                .containsExactly("/private/salary.md", "/reports/q3.md");
    }

    @Test
    void editingAFileThatWasNotReadIsRejected() {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall(
                        "call_edit", "edit_file", Map.of("path", "plan.md", "old_string", "草稿", "new_string", "定稿")))
                .respond(r -> r.text("需要先读"))
                .build();
        WorkerFixture fixture = fixture(model, FILE_TOOLS_ALLOWED);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        seed(session, "plan.md", "计划草稿");
        fixture.send(node, session, "改成定稿", Delivery.QUEUE);

        node.claimAndRun();

        assertThat(toolResult(fixture.events(node, session), "call_edit"))
                .contains("\"state\": \"error\"", "must read 'plan.md' with read_file");
        assertThat(DbAssertions.list(
                        "SELECT inline_content ->> 'content' FROM rt.workspace_entries WHERE key = '/plan.md'",
                        String.class))
                .containsExactly("计划草稿");
    }

    /** 直接经工作区存储放一个文件，模拟别的线程或上传写入的文件。 */
    private static void seed(TestSession session, String path, String content) {
        DatabaseWorkspaceStore store = new DatabaseWorkspaceStore(
                new WorkspaceEntryRepository(RuntimeTestDatabase.dataSource()),
                RuntimeTestObjectStore.get(),
                RuntimeTestDatabase.transactions(),
                java.time.Clock.systemUTC());
        PlatformContexts.run(
                session.context(),
                () -> store.put(
                        List.of(session.tenantId(), session.projectId(), END_USER, session.sessionId()),
                        "/" + path,
                        Map.of("content", content, "encoding", "utf-8")));
    }

    private static List<Path> filesModifiedAfter(Path root, Instant since) {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isAfter(since);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
