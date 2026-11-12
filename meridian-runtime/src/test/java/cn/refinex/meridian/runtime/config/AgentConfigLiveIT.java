package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.engine.spi.spec.CompactionSpec;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.KnowledgeSpec;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.approval.app.PolicyStorePort;
import cn.refinex.meridian.runtime.approval.infra.ControlPlanePolicyStore;
import cn.refinex.meridian.runtime.config.StubControlPlane.CatalogStub;
import cn.refinex.meridian.runtime.session.app.AgentConfigProvider;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.session.infra.ControlPlaneAgentConfigProvider;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * 真实模型测试（T15b-A01 的 {@code -Plive} 部分，ADR-0047）：runtime 的智能体配置与模型目录都来自 control-plane
 * （{@link StubControlPlane} 按内部接口规范返回已发布版本、环境修订、模型与凭据，凭据值是环境变量 {@code DASHSCOPE_API_KEY}，只经
 * {@code secrets:resolve} 交给 runtime），开发配置目录指向不存在的路径（不读 {@code dev-config/}）；业务方用 API Key 按智能体 ID 创建会话，
 * 生产装配的 Worker 用 DashScope 的模型完成一轮对话。管理 API 一侧（建账号、模型、凭据、智能体、发布、环境）的真实进程链路是
 * {@code scripts/dev/agent-smoke.sh --dashscope}。没有密钥时跳过；1 次调用，最大输出 64 token。
 */
@Tag("live")
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class AgentConfigLiveIT {

    private static final String DASHSCOPE = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String CHAT_MODEL =
            Objects.requireNonNullElse(System.getenv("MERIDIAN_LIVE_CHAT_MODEL"), "qwen-plus");
    private static final StubControlPlane CONTROL_PLANE = StubControlPlane.start();
    private static final Path DIRECTORY = directory();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
        SpringContainerProperties.minio(registry);
        registry.add("meridian.security.service-client.control-plane-url", CONTROL_PLANE::baseUrl);
        registry.add("meridian.security.service-jwt.client-secret", () -> StubControlPlane.RUNTIME_SECRET);
        registry.add("meridian.runtime.agent-config.source", () -> "control-plane");
        registry.add("meridian.runtime.model-routing.catalog-source", () -> "control-plane");
        // 不存在的开发目录：智能体、环境、供应商都只能来自 control-plane
        registry.add(
                "meridian.runtime.dev-agent-config.directory",
                () -> DIRECTORY.resolve("missing-agents").toString());
        registry.add(
                "meridian.runtime.model-routing.providers-directory",
                () -> DIRECTORY.resolve("missing-providers").toString());
        registry.add(
                "meridian.runtime.model-routing.budgets-directory",
                () -> DIRECTORY.resolve("budgets").toString());
        registry.add(
                "meridian.runtime.workspace.local-root",
                () -> DIRECTORY.resolve("local-workspace").toString());
        // fake-ip 透明代理把公网域名解析到 198.18.0.0/15（本机如此）：显式放行，其余与默认策略相同
        List<String> networks = new ArrayList<>(EndpointPolicy.DEFAULT_PRIVATE_NETWORKS);
        networks.add("198.18.0.0/15");
        for (int i = 0; i < networks.size(); i++) {
            String network = networks.get(i);
            registry.add("meridian.runtime.model-routing.private-networks[" + i + "]", () -> network);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TurnWorkerLifecycle lifecycle;

    @Autowired
    private AgentConfigProvider configs;

    @Autowired
    private PolicyStorePort policies;

    private static TurnWorkerLifecycle worker;

    @BeforeAll
    static void requireApiKey() {
        String key = System.getenv("DASHSCOPE_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "没有设置 DASHSCOPE_API_KEY，跳过真实模型测试");
    }

    @AfterAll
    static void stopEverything() {
        if (worker != null) {
            worker.stop();
        }
        GracefulShutdownManager.getInstance().resetForTesting();
        CONTROL_PLANE.close();
    }

    @Test
    void aSessionOnAPublishedAgentCompletesARealTurnWithEverythingFromControlPlane(CapturedOutput output) {
        worker = lifecycle;
        assertThat(configs).isInstanceOf(ControlPlaneAgentConfigProvider.class);
        assertThat(policies).isInstanceOf(ControlPlanePolicyStore.class);
        String tenantId = Ids.newId(IdPrefix.TENANT);
        String projectId = Ids.newId(IdPrefix.PROJECT);
        String secretId = Ids.newId(IdPrefix.SECRET);
        String modelId = Ids.newId(IdPrefix.MODEL);
        String agentId = Ids.newId(IdPrefix.AGENT);
        String versionId = Ids.newId(IdPrefix.AGENT_VERSION);
        String environmentId = Ids.newId(IdPrefix.ENVIRONMENT);
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        CONTROL_PLANE.quotas.put(tenantId, 5);
        CONTROL_PLANE.secret(secretId, tenantId, null, apiKey);
        CONTROL_PLANE.model(new CatalogStub(
                modelId,
                tenantId,
                Ids.newId(IdPrefix.PROVIDER_ACCOUNT),
                DASHSCOPE,
                secretId,
                "prb_live",
                1,
                CHAT_MODEL));
        CONTROL_PLANE.publish(
                tenantId,
                projectId,
                new ResolvedAgentSpec(
                        agentId,
                        versionId,
                        "live-agent",
                        new ModelSpec(modelId, null, 64, null),
                        "你是简洁的助手，用一句中文回答。",
                        Set.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        PermissionPolicy.empty(),
                        CompactionSpec.defaults(),
                        false,
                        KnowledgeSpec.none(),
                        3,
                        false));
        CONTROL_PLANE.environment(
                tenantId, projectId, new EnvironmentSpec(environmentId, 1, EnvironmentSpec.Tier.NONE, null, List.of()));
        ApiTestClient api = new ApiTestClient(
                port, CONTROL_PLANE.issue(tenantId, projectId, 600, "sessions:read", "sessions:write"));

        ApiResponse created =
                api.createSession("{\"agent\": \"" + agentId + "\", \"environment\": \"" + environmentId + "\"}", null);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        String sessionId = created.text("id");
        assertThat(created.text("agent_version_id")).isEqualTo(versionId);
        assertThat(api.sendMessage(sessionId, "用一句话介绍你自己。").status()).isEqualTo(202);

        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(types(api, sessionId)).contains("session.status_idle"));
        List<JsonNode> events = api.listEvents(sessionId, "limit=100")
                .body()
                .get("data")
                .valueStream()
                .toList();
        assertThat(types(api, sessionId)).contains("agent.message").doesNotContain("session.error");
        String answer = events.stream()
                .filter(event -> event.get("type").asString().equals("agent.message"))
                .flatMap(event -> event.get("data").get("content").valueStream())
                .map(block -> block.path("text").asString(""))
                .reduce("", String::concat);
        assertThat(answer).isNotBlank();
        assertThat(CONTROL_PLANE.configCalls.get()).as("智能体配置经内部接口读取").isPositive();
        assertThat(CONTROL_PLANE.secretResolutions.get()).as("凭据经凭据库解析").isPositive();
        JsonNode usage = JsonMappers.lenient()
                .readTree(DbAssertions.list(
                                "SELECT payload::text FROM rt.outbox WHERE topic = 'usage' AND payload->>'tenant_id' = ?",
                                String.class,
                                tenantId)
                        .getFirst());
        assertThat(usage.get("upstream_model").asString()).isEqualTo(CHAT_MODEL);
        assertThat(usage.get("usage_source").asString()).isEqualTo("provider");
        assertThat(usage.get("agent_version_id").asString()).isEqualTo(versionId);
        assertThat(output.getAll()).doesNotContain(apiKey);
        System.out.println(
                "T15b-A01 live: model=" + CHAT_MODEL + " session=" + sessionId + " agent_version=" + versionId
                        + " answer_chars=" + answer.length() + " input_tokens="
                        + usage.get("input_tokens").asInt()
                        + " output_tokens=" + usage.get("output_tokens").asInt());
    }

    private static List<String> types(ApiTestClient api, String sessionId) {
        return api.listEvents(sessionId, "limit=100")
                .body()
                .get("data")
                .valueStream()
                .map(event -> event.get("type").asString())
                .toList();
    }

    private static Path directory() {
        try {
            Path directory = Files.createTempDirectory("agent-config-live-it");
            Files.createDirectories(directory.resolve("budgets"));
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
