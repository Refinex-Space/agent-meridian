package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.SseTestClient;
import cn.refinex.meridian.runtime.support.SseTestClient.Connection;
import cn.refinex.meridian.runtime.support.SseTestClient.Frame;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import cn.refinex.meridian.testsupport.model.ScriptedOpenAiServer;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * 子智能体的端到端（T13）：经 HTTP 启动的 runtime 用生产的装配（平台工具含 {@code spawn_subagent}、{@code none} 档工作区、
 * {@code RoutedModelFactory}、线程登记与事件写入），供应商是 {@link ScriptedOpenAiServer}。父智能体与子智能体用两个模型 ID，子智能体
 * 的任务以 {@code #script:<名称>} 开头，脚本化模型服务据此为子线程选脚本。
 *
 * <p>覆盖 T13-A01（经 API 的完整路径）、A03（P12：两个同名子智能体并行，事件互不混淆，经 API 中断后都停止）、A04（显式 ask 规则
 * 在子线程上被改写为拒绝）、A05（超时）、A06（拉起上限的配置）、A07（用量计入父会话、带子线程 ID）、A08（线程列表、线程流与
 * {@code ?threads=main}），以及删除会话时清理子线程的智能体状态。
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class SubagentSessionIT {

    private static final String AGENT = "agt_e2e_subagent";
    private static final String ASK_AGENT = "agt_e2e_subagent_ask";
    private static final String TIMEOUT_AGENT = "agt_e2e_subagent_timeout";
    private static final String ENVIRONMENT = "env_e2e_subagent";
    private static final String PARENT_MODEL = "mdl_e2e_parent";
    private static final String CHILD_MODEL = "mdl_e2e_child";
    private static final Duration WAIT = Duration.ofSeconds(40);
    private static final Set<String> THREAD_EVENTS =
            Set.of("session.thread_created", "session.thread_status_running", "session.thread_status_idle");

    private static final ScriptedOpenAiServer SERVER = ScriptedOpenAiServer.builder()
            .script("""
                    name: parent-spawn
                    description: 拉起一个子智能体写文件，拿到结果后回答
                    steps:
                      - tool_calls:
                          - name: spawn_subagent
                            arguments: {subagent: researcher, task: "#script:child-write 把住宿标准写进 notes/hotel.md"}
                      - text: 子智能体已完成。
                    """)
            .script("""
                    name: child-write
                    description: 子智能体写一个文件后回答
                    steps:
                      - tool_calls:
                          - name: write_file
                            arguments: {path: notes/hotel.md, content: 住宿标准为每晚 500 元}
                      - text: 已写入 notes/hotel.md。
                    """)
            .script("""
                    name: parent-spawn-two
                    description: 同一次推理并行拉起两个同名子智能体
                    steps:
                      - tool_calls:
                          - name: spawn_subagent
                            arguments: {subagent: researcher, task: "#script:child-slow-a"}
                          - name: spawn_subagent
                            arguments: {subagent: researcher, task: "#script:child-slow-b"}
                      - text: 两个子智能体都结束了。
                    """)
            .script("""
                    name: child-slow-a
                    description: 逐字慢速输出甲
                    steps:
                      - text: %s
                        chunk_size: 1
                        delay_ms: 200
                    """.formatted("甲".repeat(80)))
            .script("""
                    name: child-slow-b
                    description: 逐字慢速输出乙
                    steps:
                      - text: %s
                        chunk_size: 1
                        delay_ms: 200
                    """.formatted("乙".repeat(80)))
            .script("""
                    name: parent-spawn-slow
                    description: 拉起一个慢速的子智能体
                    steps:
                      - tool_calls:
                          - name: spawn_subagent
                            arguments: {subagent: researcher, task: "#script:child-slow-a"}
                      - text: 子智能体超时了。
                    """)
            .script("""
                    name: parent-spawn-three
                    description: 同一次推理拉起三个子智能体，超过每轮上限
                    steps:
                      - tool_calls:
                          - name: spawn_subagent
                            arguments: {subagent: researcher, task: 第一个}
                          - name: spawn_subagent
                            arguments: {subagent: researcher, task: 第二个}
                          - name: spawn_subagent
                            arguments: {subagent: researcher, task: 第三个}
                      - text: 三个调用都有结果了。
                    """)
            .start();
    private static final Path DIRECTORY = writeConfiguration();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
        SpringContainerProperties.minio(registry);
        ApiTestKeys.register(registry);
        registry.add(
                "meridian.runtime.dev-agent-config.directory",
                () -> DIRECTORY.resolve("agents").toString());
        registry.add(
                "meridian.runtime.model-routing.providers-directory",
                () -> DIRECTORY.resolve("providers").toString());
        registry.add(
                "meridian.runtime.model-routing.budgets-directory",
                () -> DIRECTORY.resolve("budgets").toString());
        registry.add(
                "meridian.runtime.workspace.local-root",
                () -> DIRECTORY.resolve("local-workspace").toString());
        // 每轮拉起上限经配置生效（A06），并行的两个子智能体仍在上限之内
        registry.add("meridian.runtime.worker.max-subagents-per-turn", () -> "2");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TurnWorkerLifecycle lifecycle;

    private static TurnWorkerLifecycle worker;

    @BeforeEach
    void reset() {
        worker = lifecycle;
        SERVER.clearCalls();
    }

    @AfterAll
    static void stopWorkerAndServer() {
        if (worker != null) {
            worker.stop();
        }
        GracefulShutdownManager.getInstance().resetForTesting();
        SERVER.close();
    }

    @Test
    void subagentRunsOnItsOwnThreadAndTheThreadApisServeItsData() {
        ApiTestClient api = api();
        String sessionId = createSession(api, AGENT);
        SseTestClient sse = new SseTestClient(port, ApiTestKeys.KEY_A);
        Connection all = sse.open("/v1/sessions/" + sessionId + "/stream", null);
        Connection main = sse.open("/v1/sessions/" + sessionId + "/stream?threads=main", null);
        try {
            assertThat(api.sendMessage(sessionId, "#script:parent-spawn").status())
                    .isEqualTo(202);
            List<Frame> frames = all.await(idle("end_turn"), WAIT);

            // 线程列表：主线程在前，子线程记录父线程、父工具调用与子智能体名，结束后空闲
            JsonNode threads = api.listThreads(sessionId, null).body().get("data");
            assertThat(threads).hasSize(2);
            String mainThread = threads.get(0).get("id").asString();
            assertThat(threads.get(0).get("parent_thread_id").isNull()).isTrue();
            JsonNode child = threads.get(1);
            String childThread = child.get("id").asString();
            String spawnUseId = frames.stream()
                    .filter(f -> f.event().equals("agent.tool_use")
                            && f.envelope().get("data").get("name").asString().equals("spawn_subagent"))
                    .findFirst()
                    .orElseThrow()
                    .envelope()
                    .get("data")
                    .get("tool_use_id")
                    .asString();
            assertThat(child.get("parent_thread_id").asString()).isEqualTo(mainThread);
            assertThat(child.get("parent_tool_use_id").asString()).isEqualTo(spawnUseId);
            assertThat(child.get("agent_name").asString()).isEqualTo("researcher");
            assertThat(child.get("status").asString()).isEqualTo("idle");

            // 会话流含全部线程：子线程的事件依次是创建、运行、工具调用、消息、空闲，父智能体拿到子智能体的最终消息
            List<String> childEvents = frames.stream()
                    .filter(f -> childThread.equals(threadOf(f)))
                    .map(SubagentSessionIT::describe)
                    .toList();
            assertThat(childEvents)
                    .containsSubsequence(
                            "session.thread_created",
                            "session.thread_status_running",
                            "agent.tool_use:write_file",
                            "agent.tool_result:write_file",
                            "agent.message:已写入 notes/hotel.md。",
                            "session.thread_status_idle:end_turn");
            assertThat(childEvents).noneMatch(e -> e.startsWith("session.status_"));
            assertThat(frames.stream()
                            .filter(f -> f.event().equals("agent.tool_result") && mainThread.equals(threadOf(f)))
                            .map(SubagentSessionIT::describe))
                    .containsExactly("agent.tool_result:spawn_subagent");
            assertThat(resultText(frames, spawnUseId)).isEqualTo("已写入 notes/hotel.md。");
            assertThat(frames.stream()
                            .filter(f -> f.id() != null)
                            .map(Frame::seq)
                            .toList())
                    .as("会话流的 seq 连续")
                    .isSorted()
                    .doesNotHaveDuplicates();

            // ?threads=main：主线程、会话级事件与线程状态事件，不含子线程的其他事件
            List<Frame> mainFrames = main.await(idle("end_turn"), WAIT);
            assertThat(mainFrames)
                    .allSatisfy(f -> assertThat(threadOf(f) == null
                                    || mainThread.equals(threadOf(f))
                                    || THREAD_EVENTS.contains(f.event()))
                            .as(describe(f))
                            .isTrue());
            assertThat(mainFrames.stream().map(Frame::event))
                    .contains("session.thread_created", "session.thread_status_running", "session.thread_status_idle")
                    .doesNotContain("span.model_request_start:" + childThread);
            assertThat(mainFrames.stream().filter(f -> childThread.equals(threadOf(f))))
                    .allSatisfy(f -> assertThat(THREAD_EVENTS).contains(f.event()));

            // 线程流：只有子线程的事件
            Connection childStream =
                    sse.open("/v1/sessions/" + sessionId + "/threads/" + childThread + "/stream", null);
            try {
                List<Frame> childFrames = childStream.await(
                        list -> list.stream().anyMatch(f -> f.event().equals("session.thread_status_idle")), WAIT);
                assertThat(childFrames.stream().filter(f -> f.id() != null))
                        .isNotEmpty()
                        .allSatisfy(f -> assertThat(threadOf(f)).isEqualTo(childThread));
                assertThat(childFrames.stream().map(SubagentSessionIT::describe))
                        .contains("agent.tool_use:write_file");
            } finally {
                childStream.close();
            }

            // A07：子智能体的用量计入父会话，记录带子线程 ID 与子智能体的模型
            List<Map<String, Object>> usage = usage(sessionId);
            assertThat(usage)
                    .filteredOn(u -> mainThread.equals(u.get("thread_id")))
                    .hasSize(2)
                    .allSatisfy(u -> assertThat(u).containsEntry("model_id", PARENT_MODEL));
            assertThat(usage)
                    .filteredOn(u -> childThread.equals(u.get("thread_id")))
                    .hasSize(2)
                    .allSatisfy(u -> {
                        assertThat(u).containsEntry("model_id", CHILD_MODEL);
                        assertThat(u).containsEntry("session_id", sessionId);
                        assertThat(u).containsEntry("turn_id", turnOf(frames, mainThread));
                    });
            // 子线程的工具调用记在子线程上，权限判定写审计
            assertThat(DbAssertions.list(
                            "SELECT thread_id FROM rt.tool_invocations WHERE session_id = ? AND tool_name = 'write_file'",
                            String.class,
                            sessionId))
                    .containsExactly(childThread);
            assertThat(audit(sessionId, "permission.decided")).anySatisfy(a -> {
                assertThat(a).containsEntry("thread_id", childThread);
                assertThat(a).containsEntry("tool_name", "write_file");
            });

            // 删除会话时清理全部线程（含子线程）的智能体状态
            assertThat(states(mainThread, childThread)).isPositive();
            assertThat(api.deleteSession(sessionId).status()).isEqualTo(202);
            assertThat(states(mainThread, childThread)).isZero();
        } finally {
            all.close();
            main.close();
        }
    }

    /** P12：两个同名子智能体并行，事件互不混淆；经 API 中断父线程，两条子线程都停止。 */
    @Test
    void parallelSameNameSubagentsAreIsolatedAndStopWhenTheParentIsInterrupted() {
        ApiTestClient api = api();
        String sessionId = createSession(api, AGENT);
        Connection all = new SseTestClient(port, ApiTestKeys.KEY_A)
                .open("/v1/sessions/" + sessionId + "/stream?event_deltas=true", null);
        try {
            assertThat(api.sendMessage(sessionId, "#script:parent-spawn-two").status())
                    .isEqualTo(202);
            List<Frame> streaming = all.await(
                    list -> deltaThreads(list).size() == 2
                            && deltaThreads(list).values().stream().allMatch(text -> text.length() >= 3),
                    WAIT);
            String mainThread = streaming.stream()
                    .filter(f -> f.event().equals("session.status_running"))
                    .map(SubagentSessionIT::threadOf)
                    .findFirst()
                    .orElseThrow();

            ApiResponse interrupt = api.sendEvents(sessionId, "{\"events\": [{\"type\": \"user.interrupt\"}]}", null);
            assertThat(interrupt.status()).isEqualTo(202);
            List<Frame> frames = all.await(idle("interrupted"), WAIT);

            Map<String, String> deltas = deltaThreads(frames);
            assertThat(deltas).hasSize(2);
            assertThat(deltas.values())
                    .as("每条子线程的增量只来自它自己的脚本")
                    .anySatisfy(text -> assertThat(text).matches("甲+"))
                    .anySatisfy(text -> assertThat(text).matches("乙+"));
            assertThat(deltas.values())
                    .allSatisfy(text -> assertThat(text.length()).as("中断后不再输出").isLessThan(80));
            for (String childThread : deltas.keySet()) {
                assertThat(frames.stream()
                                .filter(f -> childThread.equals(threadOf(f)))
                                .map(SubagentSessionIT::describe)
                                .filter(e -> !e.startsWith("event_"))
                                .toList())
                        .first()
                        .isEqualTo("session.thread_created");
                assertThat(frames.stream()
                                .filter(f -> childThread.equals(threadOf(f)) && f.id() != null)
                                .map(SubagentSessionIT::describe)
                                .toList())
                        .last()
                        .isEqualTo("session.thread_status_idle:interrupted");
            }
            assertThat(frames.stream()
                            .filter(f -> f.event().equals("agent.tool_result") && mainThread.equals(threadOf(f)))
                            .map(f -> f.envelope()
                                    .get("data")
                                    .get("content")
                                    .get(0)
                                    .get("text")
                                    .asString()))
                    .hasSize(2)
                    .allSatisfy(text -> assertThat(text).contains("was interrupted before it finished"));
            JsonNode threads = api.listThreads(sessionId, null).body().get("data");
            assertThat(threads).hasSize(3);
            assertThat(Set.of(
                            threads.get(1).get("parent_tool_use_id").asString(),
                            threads.get(2).get("parent_tool_use_id").asString()))
                    .as("两条子线程各对应一次 spawn_subagent 调用")
                    .hasSize(2);
            assertThat(SERVER.calls().stream().filter(c -> "parent-spawn-two".equals(c.script())))
                    .as("父线程被中断后没有再推理")
                    .hasSize(1);
        } finally {
            all.close();
        }
    }

    /** A04：父智能体对子智能体的工具有显式 ask 规则时，子线程上改写为拒绝，不产生待处理动作。 */
    @Test
    void explicitAskRuleIsDeniedOnTheSubagentThread() {
        ApiTestClient api = api();
        String sessionId = createSession(api, ASK_AGENT);
        Connection all = new SseTestClient(port, ApiTestKeys.KEY_A).open("/v1/sessions/" + sessionId + "/stream", null);
        try {
            assertThat(api.sendMessage(sessionId, "#script:parent-spawn").status())
                    .isEqualTo(202);
            List<Frame> frames = all.await(idle("end_turn"), WAIT);

            Frame denied = frames.stream()
                    .filter(f -> f.event().equals("agent.tool_result")
                            && f.envelope().get("data").get("name").asString().equals("write_file"))
                    .findFirst()
                    .orElseThrow();
            assertThat(denied.envelope().get("data").get("state").asString()).isEqualTo("denied");
            assertThat(denied.envelope().get("data").get("permission").asString())
                    .isEqualTo("denied");
            assertThat(frames.stream().map(Frame::event)).doesNotContain("session.status_requires_action");
            assertThat(api.listPendingActions(sessionId, null).body().get("data"))
                    .isEmpty();
            assertThat(DbAssertions.list(
                            "SELECT count(*) FROM rt.tool_invocations WHERE session_id = ? AND tool_name = 'write_file'",
                            Long.class,
                            sessionId))
                    .as("被拒绝的调用没有执行")
                    .containsExactly(0L);
        } finally {
            all.close();
        }
    }

    /** A05：超时后取消子智能体，子线程以 interrupted 结束，父智能体收到超时结果并继续。 */
    @Test
    void timedOutSubagentIsCancelledAndTheParentContinues() {
        ApiTestClient api = api();
        String sessionId = createSession(api, TIMEOUT_AGENT);
        Connection all = new SseTestClient(port, ApiTestKeys.KEY_A).open("/v1/sessions/" + sessionId + "/stream", null);
        try {
            assertThat(api.sendMessage(sessionId, "#script:parent-spawn-slow").status())
                    .isEqualTo(202);
            List<Frame> frames = all.await(idle("end_turn"), WAIT);

            assertThat(frames.stream().map(SubagentSessionIT::describe))
                    .contains("session.thread_status_idle:interrupted", "agent.message:子智能体超时了。");
            Frame result = frames.stream()
                    .filter(f -> f.event().equals("agent.tool_result"))
                    .findFirst()
                    .orElseThrow();
            assertThat(result.envelope().get("data").get("state").asString()).isEqualTo("error");
            assertThat(result.envelope()
                            .get("data")
                            .get("content")
                            .get(0)
                            .get("text")
                            .asString())
                    .contains("did not finish within 1 seconds and was cancelled");
        } finally {
            all.close();
        }
    }

    /** A06：每轮拉起上限按配置（本类为 2）生效，多余的调用被拒绝，不创建子线程。 */
    @Test
    void spawnsBeyondTheConfiguredLimitAreRejected() {
        ApiTestClient api = api();
        String sessionId = createSession(api, AGENT);
        Connection all = new SseTestClient(port, ApiTestKeys.KEY_A).open("/v1/sessions/" + sessionId + "/stream", null);
        try {
            assertThat(api.sendMessage(sessionId, "#script:parent-spawn-three").status())
                    .isEqualTo(202);
            List<Frame> frames = all.await(idle("end_turn"), WAIT);

            List<String> results = frames.stream()
                    .filter(f -> f.event().equals("agent.tool_result"))
                    .map(f -> f.envelope().get("data").get("state").asString() + ":"
                            + f.envelope()
                                    .get("data")
                                    .get("content")
                                    .get(0)
                                    .get("text")
                                    .asString())
                    .toList();
            assertThat(results).hasSize(3);
            assertThat(results).filteredOn(r -> r.startsWith("success:echo: ")).hasSize(2);
            assertThat(results)
                    .filteredOn(r -> r.startsWith("error:") && r.contains("limit of subagents per turn"))
                    .hasSize(1);
            assertThat(api.listThreads(sessionId, null).body().get("data")).hasSize(3);
        } finally {
            all.close();
        }
    }

    // ---- 辅助 ----

    private ApiTestClient api() {
        return new ApiTestClient(port, ApiTestKeys.KEY_A);
    }

    private static String createSession(ApiTestClient api, String agent) {
        ApiResponse created =
                api.createSession("{\"agent\": \"" + agent + "\", \"environment\": \"" + ENVIRONMENT + "\"}", null);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        return created.text("id");
    }

    private static Predicate<List<Frame>> idle(String stopReason) {
        return frames -> frames.stream()
                .anyMatch(f -> f.event().equals("session.status_idle")
                        && f.envelope()
                                .get("data")
                                .get("stop_reason")
                                .asString()
                                .equals(stopReason));
    }

    private static String threadOf(Frame frame) {
        JsonNode thread = frame.envelope().get("thread_id");
        return thread == null || thread.isNull() ? null : thread.asString();
    }

    private static String turnOf(List<Frame> frames, String mainThread) {
        return frames.stream()
                .filter(f -> f.event().equals("session.status_running") && mainThread.equals(threadOf(f)))
                .findFirst()
                .orElseThrow()
                .envelope()
                .get("turn_id")
                .asString();
    }

    /** 子线程（非主线程）上流式文本增量按线程拼接：线程 ID → 文本。 */
    private static Map<String, String> deltaThreads(List<Frame> frames) {
        String mainThread = frames.stream()
                .filter(f -> f.event().equals("session.status_running"))
                .map(SubagentSessionIT::threadOf)
                .findFirst()
                .orElse(null);
        return frames.stream()
                .filter(f -> f.event().equals("event_delta")
                        && threadOf(f) != null
                        && !threadOf(f).equals(mainThread)
                        && f.envelope().get("data").get("kind").asString().equals("text"))
                .collect(Collectors.groupingBy(
                        SubagentSessionIT::threadOf,
                        Collectors.mapping(
                                f -> f.envelope().get("data").get("delta").asString(), Collectors.joining())));
    }

    private static String resultText(List<Frame> frames, String toolUseId) {
        return frames.stream()
                .filter(f -> f.event().equals("agent.tool_result")
                        && f.envelope()
                                .get("data")
                                .get("tool_use_id")
                                .asString()
                                .equals(toolUseId))
                .findFirst()
                .orElseThrow()
                .envelope()
                .get("data")
                .get("content")
                .get(0)
                .get("text")
                .asString();
    }

    /** 事件的简短表示：类型，工具事件加工具名，消息加文本，状态加停止原因。 */
    private static String describe(Frame frame) {
        JsonNode data = frame.envelope().get("data");
        return switch (frame.event()) {
            case "agent.tool_use", "agent.tool_result" ->
                frame.event() + ":" + data.get("name").asString();
            case "agent.message" ->
                frame.event() + ":" + data.get("content").get(0).get("text").asString();
            case "session.status_idle", "session.thread_status_idle" ->
                frame.event() + ":" + data.get("stop_reason").asString();
            default -> frame.event();
        };
    }

    private static List<Map<String, Object>> usage(String sessionId) {
        return outbox("usage", sessionId);
    }

    private static List<Map<String, Object>> audit(String sessionId, String action) {
        return outbox("audit", sessionId).stream()
                .filter(payload -> action.equals(payload.get("action")))
                .toList();
    }

    private static List<Map<String, Object>> outbox(String topic, String sessionId) {
        return DbAssertions.list(
                        "SELECT payload::text FROM rt.outbox WHERE topic = ? AND payload->>'session_id' = ?"
                                + " ORDER BY created_at, id",
                        String.class,
                        topic,
                        sessionId)
                .stream()
                .map(json -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = JsonMappers.lenient().readValue(json, Map.class);
                    return payload;
                })
                .toList();
    }

    private static long states(String mainThread, String childThread) {
        return DbAssertions.list(
                        "SELECT count(*) FROM rt.agent_states WHERE thread_id IN (?, ?)",
                        Long.class,
                        mainThread,
                        childThread)
                .getFirst();
    }

    /** 两个模型 ID 指向同一个脚本化模型服务；三个智能体版本共用一个 {@code none} 档环境。 */
    private static Path writeConfiguration() {
        try {
            Path directory = Files.createTempDirectory("subagent-session-it");
            Path agents = Files.createDirectories(directory.resolve("agents"));
            Path providers = Files.createDirectories(directory.resolve("providers"));
            Files.createDirectories(directory.resolve("budgets"));
            Files.writeString(
                    providers.resolve("scripted.yaml"), """
                    kind: provider_account
                    provider_account_id: prv_e2e_subagent
                    provider: openai_compatible
                    base_url: %s
                    models:
                      - model_id: %s
                        upstream_model: scripted-parent
                        context_window: 131072
                        max_output_tokens: 1024
                      - model_id: %s
                        upstream_model: scripted-child
                        context_window: 131072
                        max_output_tokens: 1024
                    """.formatted(SERVER.baseUrl(), PARENT_MODEL, CHILD_MODEL));
            Files.writeString(agents.resolve("agent.yaml"), agent(AGENT, "allow", "PT60S"));
            Files.writeString(agents.resolve("agent-ask.yaml"), agent(ASK_AGENT, "ask", "PT60S"));
            Files.writeString(agents.resolve("agent-timeout.yaml"), agent(TIMEOUT_AGENT, "allow", "PT1S"));
            Files.writeString(agents.resolve("environment.yaml"), """
                    kind: environment
                    environment_id: %s
                    revision: 1
                    tier: none
                    egress_allowlist: []
                    """.formatted(ENVIRONMENT));
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String agent(String agentId, String writeBehavior, String subagentTimeout) {
        return """
                kind: agent_version
                agent_id: %1$s
                agent_version_id: %1$s_v1
                name: coordinator
                model:
                  model_id: %2$s
                system_prompt: 你负责分派任务。
                tools: [spawn_subagent, read_file, write_file]
                mcp_servers: []
                skills: []
                subagents:
                  - name: researcher
                    description: 调研并把结论写进工作区。
                    model:
                      model_id: %3$s
                    system_prompt: 你是调研子智能体。
                    tools: [read_file, write_file]
                    skills: []
                    permissions:
                      mode: default
                      rules: []
                    max_iters: 10
                    timeout: %5$s
                permissions:
                  mode: default
                  rules:
                    - {tool_name: spawn_subagent, behavior: allow, layer: agent_version}
                    - {tool_name: read_file, behavior: allow, layer: agent_version}
                    - {tool_name: write_file, behavior: %4$s, layer: agent_version}
                compaction:
                  reserved_tokens: 20000
                plan_mode: false
                knowledge:
                  knowledge_base_ids: []
                  prefetch: false
                  prefetch_top_k: 5
                max_iters: 10
                expose_thinking: false
                """.formatted(agentId, PARENT_MODEL, CHILD_MODEL, writeBehavior, subagentTimeout);
    }
}
