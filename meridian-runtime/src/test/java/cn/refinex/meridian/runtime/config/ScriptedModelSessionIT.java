package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.assembly.SkillRepositoryProvider;
import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import cn.refinex.meridian.engine.agentscope.assembly.WorkspaceFilesystemProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.InMemoryWorkspaceFilesystems;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticSkillRepository;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticToolProvider;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.SseTestClient;
import cn.refinex.meridian.runtime.support.SseTestClient.Connection;
import cn.refinex.meridian.runtime.support.SseTestClient.Frame;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import cn.refinex.meridian.testsupport.model.ScriptedOpenAiServer;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

/**
 * 端到端（T09b-A03、A04）：经 HTTP 启动的 runtime 以脚本化模型服务为供应商跑完整会话。模型工厂是生产的 {@code RoutedModelFactory}
 * （测试没有声明 {@code ModelFactory}），所以模型调用走真实的 OpenAI 兼容客户端、限流、预扣与计量，供应商是
 * {@link ScriptedOpenAiServer}；工具是测试工具，文件系统与技能仓库是内存实现（T10a 之前没有生产实现）。
 *
 * <p>内置脚本各有一个用例：调用一次工具（并重复运行比较事件序列）、审批、自定义工具、长时间流式中的中断、拉起子智能体、首包前 5xx。
 */
@SpringBootTest(
        classes = {RuntimeApplication.class, ScriptedModelSessionIT.Providers.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class ScriptedModelSessionIT {

    private static final String AGENT = "agt_e2e_scripted";
    private static final String ENVIRONMENT = "env_e2e_none";
    private static final Duration WAIT = Duration.ofSeconds(30);

    private static final ScriptedOpenAiServer SERVER =
            ScriptedOpenAiServer.builder().start();
    private static final Path DIRECTORY = writeConfiguration();

    /** 测试工具，跨用例共用，每个用例前清空调用记录。 */
    static final CountingTool LOOKUP = new CountingTool("lookup_order", "查询订单", true, "订单 o-1001 已发货");

    static final CountingTool REFUND = new CountingTool("refund_order", "退款", false, "退款已提交");

    static final CountingTool SPAWN = new CountingTool("spawn_subagent", "拉起子智能体（测试替身）", false, "住宿标准为每晚 500 元");

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
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TurnWorkerLifecycle lifecycle;

    /** 本类的 Worker：用例之间一直运行，全部用例结束后停止（停止会触发 AgentScope 的 JVM 级优雅停机）。 */
    private static TurnWorkerLifecycle worker;

    @BeforeEach
    void reset() {
        worker = lifecycle;
        SERVER.clearCalls();
        List.of(LOOKUP, REFUND, SPAWN).forEach(CountingTool::clear);
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
    void aToolCallingSessionFollowsTheScriptAndRepeatsIdentically() {
        List<String> first = runToolOnce();
        List<String> second = runToolOnce();

        assertThat(second).as("同一脚本重复运行，事件序列相同").isEqualTo(first);
        assertThat(first)
                .containsSubsequence(
                        "user.message",
                        "session.status_running",
                        "agent.message:我先查一下订单。",
                        "agent.tool_use:lookup_order",
                        "agent.tool_result:lookup_order",
                        "agent.message:订单 o-1001 已发货，预计明天送达。",
                        "session.status_idle:end_turn");
        assertThat(LOOKUP.calls())
                .as("两次会话各调用一次")
                .hasSize(2)
                .allSatisfy(input -> assertThat(input).containsEntry("order_id", "o-1001"));
        assertThat(SERVER.calls().stream()
                        .filter(call -> "tool-once".equals(call.script()))
                        .map(ScriptedOpenAiServer.Call::step))
                .as("每次会话两步：工具调用、回答")
                .containsExactly(0, 1, 0, 1);
    }

    @Test
    void theApprovalScriptWaitsForAConfirmationAndThenFinishes() {
        Session session = start(null);
        try {
            session.send("#script:approval");
            session.await(types -> types.contains("session.status_requires_action"));
            JsonAction action = session.pendingAction();
            assertThat(action.type()).isEqualTo("tool_confirmation");
            assertThat(action.toolName()).isEqualTo("refund_order");
            assertThat(REFUND.calls()).as("确认之前不执行").isEmpty();

            session.respond("""
                    {"events": [{"type": "user.tool_confirmation", "action_id": "%s", "approved": true}]}
                    """.formatted(action.id()));
            List<String> types = session.await(t -> t.contains("session.status_idle:end_turn"));

            assertThat(types).containsSubsequence("agent.tool_use:refund_order", "agent.message:退款申请已处理。");
            assertThat(REFUND.calls())
                    .singleElement()
                    .satisfies(input -> assertThat(input).containsEntry("amount", 100));
        } finally {
            session.close();
        }
    }

    @Test
    void theCustomToolScriptSuspendsUntilTheCallerReturnsAResult() {
        Session session = start("""
                , "custom_tools": [{"name": "get_weather", "description": "查询天气", "side_effect": "none",
                   "input_schema": {"type": "object", "properties": {"city": {"type": "string"}}}}]
                """);
        try {
            session.send("#script:custom-tool");
            session.await(types -> types.contains("session.status_requires_action"));
            JsonAction action = session.pendingAction();
            assertThat(action.type()).isEqualTo("custom_tool_result");
            assertThat(action.toolName()).isEqualTo("get_weather");
            assertThat(action.input()).containsEntry("city", "北京");

            session.respond("""
                    {"events": [{"type": "user.custom_tool_result", "action_id": "%s",
                                 "content": [{"type": "text", "text": "晴，20 度"}]}]}
                    """.formatted(action.id()));
            List<String> types = session.await(t -> t.contains("session.status_idle:end_turn"));

            assertThat(types).containsSubsequence("agent.custom_tool_use:get_weather", "agent.message:北京今天晴，适合出行。");
        } finally {
            session.close();
        }
    }

    @Test
    void theLongStreamingScriptLeavesTimeToInterrupt() {
        Session session = start(null);
        try {
            long start = System.nanoTime();
            session.send("#script:long-stream");
            // 等到模型已经开始输出（流上出现内容增量），再中断
            session.awaitFrames(
                    frames -> frames.stream().anyMatch(f -> f.event().equals("event_delta")));
            session.respond("{\"events\": [{\"type\": \"user.interrupt\"}]}");
            List<String> types =
                    session.await(t -> t.stream().anyMatch(type -> type.startsWith("session.status_idle")));

            assertThat(types).contains("session.status_idle:interrupted");
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .as("脚本完整输出约 11 秒，中断后提前结束")
                    .isLessThan(Duration.ofSeconds(10));
        } finally {
            session.close();
        }
    }

    @Test
    void theSubagentScriptCallsSpawnSubagentWithNameAndTask() {
        Session session = start(null);
        try {
            session.send("#script:subagent");
            List<String> types = session.await(t -> t.contains("session.status_idle:end_turn"));

            assertThat(types)
                    .containsSubsequence(
                            "agent.tool_use:spawn_subagent",
                            "agent.tool_result:spawn_subagent",
                            "agent.message:子智能体已完成调研：住宿标准为每晚 500 元。");
            assertThat(SPAWN.calls()).singleElement().satisfies(input -> {
                assertThat(input).containsEntry("subagent", "researcher");
                assertThat(input).containsEntry("task", "调研差旅住宿标准");
            });
        } finally {
            session.close();
        }
    }

    @Test
    void aServerErrorBeforeTheFirstChunkIsRetriedThenEndsTheTurnAsRetryable() {
        Session session = start(null);
        try {
            session.send("#script:error-before-first-chunk");
            List<Frame> frames = session.awaitFrames(
                    received -> received.stream().anyMatch(f -> f.event().equals("session.status_rescheduled")));

            Frame error = frames.stream()
                    .filter(f -> f.event().equals("session.error"))
                    .findFirst()
                    .orElseThrow();
            assertThat(error.envelope().get("data").get("retry_status").asString())
                    .isEqualTo("retryable");
            assertThat(error.envelope().get("data").get("code").asString()).isEqualTo("model.provider_unavailable");
            Frame rescheduled = frames.stream()
                    .filter(f -> f.event().equals("session.status_rescheduled"))
                    .findFirst()
                    .orElseThrow();
            assertThat(rescheduled.envelope().get("data").get("reason").asString())
                    .isEqualTo("retryable_error");
            assertThat(SERVER.calls().stream()
                            .filter(call -> "error-before-first-chunk".equals(call.script()))
                            .count())
                    .as("RoutedModel 首次加 2 次重试")
                    .isGreaterThanOrEqualTo(3);
        } finally {
            session.close();
            // 删除会话取消重新排队的轮次，它不再占用 Worker 与脚本服务
            session.api().deleteSession(session.id());
        }
    }

    // ---- 辅助 ----

    /** 跑一次 tool-once 会话，返回归一化的事件序列（类型加工具名或文本，不含 ID 与时间）。 */
    private List<String> runToolOnce() {
        Session session = start(null);
        try {
            session.send("#script:tool-once");
            return session.await(types -> types.contains("session.status_idle:end_turn"));
        } finally {
            session.close();
        }
    }

    private Session start(String extraFields) {
        ApiTestClient api = new ApiTestClient(port, ApiTestKeys.KEY_A);
        ApiResponse created = api.createSession(
                "{\"agent\": \"" + AGENT + "\", \"environment\": \"" + ENVIRONMENT + "\""
                        + (extraFields == null ? "" : extraFields) + "}",
                null);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        String sessionId = created.text("id");
        Connection stream = new SseTestClient(port, ApiTestKeys.KEY_A)
                .open("/v1/sessions/" + sessionId + "/stream?event_deltas=true", null);
        return new Session(api, sessionId, stream);
    }

    /** 一个会话与它的事件流。 */
    private record Session(ApiTestClient api, String id, Connection stream) {

        void send(String text) {
            assertThat(api.sendMessage(id, text).status()).isEqualTo(202);
        }

        void respond(String body) {
            assertThat(api.sendEvents(id, body, null).status()).isEqualTo(202);
        }

        List<Frame> awaitFrames(Predicate<List<Frame>> condition) {
            return stream.await(condition, WAIT);
        }

        List<String> await(Predicate<List<String>> condition) {
            return normalize(stream.await(frames -> condition.test(normalize(frames)), WAIT));
        }

        JsonAction pendingAction() {
            ApiResponse response = api.listPendingActions(id, "status=pending");
            var action = response.body().get("data").get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> input =
                    cn.refinex.meridian.common.json.JsonMappers.lenient().convertValue(action.get("input"), Map.class);
            return new JsonAction(
                    action.get("id").asString(),
                    action.get("type").asString(),
                    action.get("tool_name").asString(),
                    input);
        }

        void close() {
            stream.close();
        }

        /** 持久化事件的归一化表示：类型，工具事件加工具名，消息加文本，状态加停止原因；流上的增量与 ping 不计入。 */
        private static List<String> normalize(List<Frame> frames) {
            return frames.stream()
                    .filter(frame -> frame.id() != null)
                    .map(frame -> {
                        var data = frame.envelope().get("data");
                        return switch (frame.event()) {
                            case "agent.tool_use", "agent.tool_result", "agent.custom_tool_use" ->
                                frame.event() + ":" + data.get("name").asString();
                            case "agent.message" ->
                                frame.event() + ":"
                                        + data.get("content").get(0).get("text").asString();
                            case "session.status_idle" ->
                                frame.event() + ":" + data.get("stop_reason").asString();
                            default -> frame.event();
                        };
                    })
                    .toList();
        }
    }

    /** 待处理动作中用到的字段。 */
    private record JsonAction(String id, String type, String toolName, Map<String, Object> input) {}

    /** 测试工具：任意对象入参，返回固定文本并记录入参。 */
    static final class CountingTool extends ToolBase {

        private final String result;
        private final List<Map<String, Object>> calls = new CopyOnWriteArrayList<>();

        CountingTool(String name, String description, boolean readOnly, String result) {
            super(ToolBase.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(Map.of("type", "object", "additionalProperties", true))
                    .readOnly(readOnly)
                    .concurrencySafe(readOnly));
            this.result = result;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            calls.add(Map.copyOf(param.getInput()));
            return Mono.fromSupplier(() -> ToolResultBlock.text(result));
        }

        List<Map<String, Object>> calls() {
            return List.copyOf(calls);
        }

        void clear() {
            calls.clear();
        }
    }

    /** 内核的另外三个提供者：测试工具、内存文件系统、空的技能仓库；模型工厂用生产的 {@code RoutedModelFactory}。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Providers {

        @Bean
        ToolProvider toolProvider() {
            return StaticToolProvider.always(LOOKUP, REFUND, SPAWN);
        }

        @Bean
        WorkspaceFilesystemProvider workspaceFilesystemProvider() {
            return new InMemoryWorkspaceFilesystems();
        }

        @Bean
        SkillRepositoryProvider skillRepositoryProvider() {
            return StaticSkillRepository.empty();
        }
    }

    /** 临时目录中的智能体与环境配置、指向脚本化模型服务的供应商目录、空的预算目录。 */
    private static Path writeConfiguration() {
        try {
            Path directory = Files.createTempDirectory("scripted-model-session-it");
            Path agents = Files.createDirectories(directory.resolve("agents"));
            Path providers = Files.createDirectories(directory.resolve("providers"));
            Files.createDirectories(directory.resolve("budgets"));
            Files.writeString(providers.resolve("scripted.yaml"), """
                    kind: provider_account
                    provider_account_id: prv_e2e_scripted
                    provider: openai_compatible
                    base_url: %s
                    models:
                      - model_id: mdl_e2e_scripted
                        upstream_model: scripted
                        context_window: 131072
                        max_output_tokens: 1024
                    """.formatted(SERVER.baseUrl()));
            Files.writeString(agents.resolve("agent.yaml"), """
                    kind: agent_version
                    agent_id: %s
                    agent_version_id: agv_e2e_scripted_1
                    name: scripted
                    model:
                      model_id: mdl_e2e_scripted
                    system_prompt: 你是测试智能体。
                    tools: []
                    mcp_servers: []
                    skills: []
                    subagents: []
                    permissions:
                      mode: default
                      rules:
                        - {tool_name: lookup_order, behavior: allow, layer: agent_version}
                        - {tool_name: spawn_subagent, behavior: allow, layer: agent_version}
                    compaction:
                      reserved_tokens: 20000
                    plan_mode: false
                    knowledge:
                      knowledge_base_ids: []
                      prefetch: false
                      prefetch_top_k: 5
                    max_iters: 10
                    expose_thinking: false
                    """.formatted(AGENT));
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
}
