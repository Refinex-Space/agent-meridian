package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.ofType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver.SandboxRequest;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.port.WorkspaceFiles;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec.SandboxSize;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.file.app.FileProperties;
import cn.refinex.meridian.runtime.file.app.FileService;
import cn.refinex.meridian.runtime.file.app.MediaTypes;
import cn.refinex.meridian.runtime.file.app.TurnFileStager;
import cn.refinex.meridian.runtime.file.domain.StoredFile;
import cn.refinex.meridian.runtime.file.infra.FileRepository;
import cn.refinex.meridian.runtime.sandbox.app.FakeSandboxDriver;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.util.unit.DataSize;

/**
 * T17b-A01：上传的文件在 {@code user.message} 中引用，轮次开始时被放进工作区，智能体用文件工具读到它（ADR-0050）。完整节点：生产的平台工具、
 * {@code none} 档工作区（PostgreSQL 与 MinIO）、发送事件的用例（引用校验与补全）与 Worker 的放置。
 */
class FileReferenceWorkerIT {

    private static final Pattern WORKSPACE_PATH = Pattern.compile("is in the workspace at (uploads/[^\\]]+)]");

    @AfterEach
    void resetGracefulShutdown() {
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    @Test
    void anUploadedFileReferencedInAMessageIsReadByTheAgentFromTheWorkspace() {
        AtomicReference<String> seenPath = new AtomicReference<>();
        ScriptedModel model = ScriptedModel.builder()
                .respondTo((request, response) -> {
                    Matcher matcher = WORKSPACE_PATH.matcher(request.lastUserText());
                    assertThat(matcher.find())
                            .as("模型收到的是工作区路径：" + request.lastUserText())
                            .isTrue();
                    seenPath.set(matcher.group(1));
                    response.toolCall("call_read", "read_file", Map.of("path", matcher.group(1)));
                })
                .respond(r -> r.text("报告的结论是收入增长 12%"))
                .build();
        WorkerFixture fixture = new WorkerFixture(
                model,
                agent -> agent.tools(PlatformToolNames.READ_FILE)
                        .permissions(new PermissionPolicy(
                                PermissionMode.DEFAULT,
                                List.of(FileToolsWorkerIT.allow(PlatformToolNames.READ_FILE, null)))));
        fixture.platform = true;
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        String report = "# Q3 报告\n收入增长 12%\n";
        StoredFile file = upload(node, session, "../../tmp/Q3 报告.md‮", report.getBytes(StandardCharsets.UTF_8));
        assertThat(file.filename()).as("去掉目录部分与双向文本覆盖符").isEqualTo("Q3 报告.md");

        // 客户端声明的类型不被采用：保存的是检测的类型（引用文件的消息按 queue 投递，见 EventAdmissionTest）
        List<StoredEvent> posted = fixture.post(
                node,
                session,
                new UserEvent.Message(
                        List.of(
                                new MessageContent.Text("帮我看一下这份报告"),
                                new MessageContent.FileRef(file.id(), null, "application/x-evil")),
                        Delivery.STEER));
        assertThat(posted.getFirst().data())
                .contains("\"filename\":\"Q3 报告.md\"")
                .contains("\"media_type\":\"" + file.mediaType() + "\"")
                .doesNotContain("application/x-evil");

        TurnResult result = node.claimAndRun();

        assertThat(result)
                .isInstanceOfSatisfying(
                        TurnResult.Finished.class, f -> assertThat(f.outcome()).isEqualTo(TurnOutcome.COMPLETED));
        String path = WorkspaceFiles.uploadPath(file.id(), "Q3 报告.md");
        assertThat(seenPath.get()).isEqualTo(path);
        List<StoredEvent> events = fixture.events(node, session);
        assertThat(FileToolsWorkerIT.toolResult(events, "call_read")).contains("Q3 报告", "收入增长 12%");
        assertThat(ofType(events, EventType.AGENT_MESSAGE))
                .last()
                .satisfies(e -> assertThat(e.data()).contains("收入增长 12%"));
        // 放进了会话工作区：命名空间 [tenant, project, end_user, session]，记下内容大小
        Map<String, Object> entry = DbAssertions.row(
                "SELECT namespace[4] AS session_id, content_size FROM rt.workspace_entries WHERE key = ?",
                WorkspaceFiles.key(path));
        assertThat(entry.get("session_id")).isEqualTo(session.sessionId());
        assertThat(entry.get("content_size")).isEqualTo((long) report.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void referencesMustBelongToTheProjectAndImagesMustBeImages() {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder().build(), agent -> agent);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        TestSession otherProject = fixture.session();
        StoredFile text = upload(node, session, "notes.txt", "纯文本".getBytes(StandardCharsets.UTF_8));
        StoredFile foreign = upload(node, otherProject, "a.txt", "别的项目".getBytes(StandardCharsets.UTF_8));
        StoredFile png = upload(node, session, "pixel.png", PNG);
        assertThat(png.mediaType()).isEqualTo("image/png");

        assertThatThrownBy(() -> fixture.post(
                        node,
                        session,
                        new UserEvent.Message(
                                List.of(
                                        new MessageContent.FileRef(foreign.id(), null, null),
                                        new MessageContent.Image(null, text.id(), "image/png")),
                                Delivery.QUEUE)))
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.violations())
                                .extracting(v -> v.field())
                                .containsExactly("events[0].content[0].file_id", "events[0].content[1].file_id"));
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.session_events WHERE session_id = ? AND type = 'user.message'",
                        Long.class,
                        session.sessionId()))
                .as("整批拒绝，不写事件")
                .containsExactly(0L);

        List<StoredEvent> accepted = fixture.post(
                node,
                session,
                new UserEvent.Message(List.of(new MessageContent.Image(null, png.id(), null)), Delivery.QUEUE));
        assertThat(accepted.getFirst().data()).contains("\"media_type\":\"image/png\"");
    }

    @Test
    void theSandboxTierWritesTheFilesIntoTheSessionSandbox() {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder().build(), agent -> agent);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        StoredFile file = upload(node, session, "data.csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        FakeSandboxDriver driver = new FakeSandboxDriver(RuntimeTestObjectStore.get());
        SandboxHandle sandbox = PlatformContexts.call(
                session.context(),
                () -> driver.create(new SandboxRequest(session.sessionId(), SandboxSize.SMALL))
                        .block());
        TurnFileStager stager = new TurnFileStager(
                new FileRepository(RuntimeTestDatabase.dataSource()),
                node.files(),
                new DatabaseWorkspaceStore(
                        new WorkspaceEntryRepository(RuntimeTestDatabase.dataSource()),
                        RuntimeTestObjectStore.get(),
                        RuntimeTestDatabase.transactions(),
                        Clock.systemUTC()),
                new StaticListableBeanFactory(Map.of("driver", driver))
                        .getBeanProvider(cn.refinex.meridian.engine.spi.driver.SandboxDriver.class),
                Clock.systemUTC());
        TurnContext turn = new TurnContext(
                PlatformContext.builder()
                        .tenantId(session.tenantId())
                        .projectId(session.projectId())
                        .sessionId(session.sessionId())
                        .threadId(session.mainThreadId())
                        .turnId("trn_staging")
                        .leaseEpoch(1L)
                        .interaction(Interaction.ATTENDED)
                        .build(),
                null,
                "agv_x",
                "env_x",
                1,
                new PermissionPolicy(PermissionMode.DEFAULT, List.of()),
                null,
                null,
                null);
        TurnInput input = new TurnInput.NewMessages(
                List.of(new InboundMessage("evt_1", List.of(new MessageContent.FileRef(file.id(), "数据.csv", null)))),
                null);

        Map<String, String> staged = PlatformContexts.call(turn.platform(), () -> stager.stage(turn, input, sandbox));

        String path = WorkspaceFiles.uploadPath(file.id(), "数据.csv");
        assertThat(staged).containsExactly(Map.entry(file.id(), path));
        assertThat(driver.written(sandbox.sandboxId())).containsOnlyKeys(path);
        assertThat(new String(driver.written(sandbox.sandboxId()).get(path), StandardCharsets.UTF_8))
                .isEqualTo("a,b\n1,2\n");
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.workspace_entries WHERE namespace[4] = ?",
                        Long.class,
                        session.sessionId()))
                .as("sandbox 档不写平台存储")
                .containsExactly(0L);
    }

    /** 1×1 的 PNG。 */
    private static final byte[] PNG = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==");

    /** 以会话所在项目的身份上传一个文件。 */
    static StoredFile upload(WorkerNode node, TestSession session, String filename, byte[] content) {
        return upload(node.files(), session, filename, content);
    }

    static StoredFile upload(FileService files, TestSession session, String filename, byte[] content) {
        PlatformContext caller = PlatformContext.builder()
                .tenantId(session.tenantId())
                .projectId(session.projectId())
                .apiKeyId("key_test")
                .build();
        try {
            Path temp = Files.createTempFile("file-reference-it-", ".part");
            try {
                Files.write(temp, content);
                return PlatformContexts.call(caller, () -> files.upload(temp, filename));
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 测试外直接组装的文件用例（与节点共用库与对象存储）。 */
    static FileService fileService() {
        return new FileService(
                new FileRepository(RuntimeTestDatabase.dataSource()),
                RuntimeTestObjectStore.get(),
                new MediaTypes(),
                new FileProperties(DataSize.ofMegabytes(32)),
                Clock.systemUTC());
    }
}
