package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.port.SecretResolver;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.config.StubControlPlane.CatalogStub;
import cn.refinex.meridian.runtime.modelrouting.app.ModelCatalogPort;
import cn.refinex.meridian.runtime.modelrouting.infra.ControlPlaneModelCatalog;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import cn.refinex.meridian.testsupport.model.ScriptedOpenAiServer;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * runtime 经 control-plane 解析模型目录与凭据（T15a-A01、A03、A04、A05 的 runtime 部分，ADR-0045）：{@code catalog-source=control-plane}，
 * 开发目录指向不存在的路径（证明不读 {@code dev-config/providers}）；control-plane 由 {@link StubControlPlane} 按内部接口规范模拟（它的
 * 管理接口到内部接口由 control-plane 自己的集成测试覆盖）。验证：一轮会话的模型调用用目录中的端点与凭据，会话后日志、事件与 outbox 中
 * 没有凭据明文；用量记录带价格表版本，新版本后新记录用新版本、旧记录不变；凭据轮换、端点修改、模型删除经 {@code config.invalidate}
 * 立即生效（不等缓存过期）；别的租户的模型与不存在相同。
 */
@SpringBootTest(
        classes = {RuntimeApplication.class, ControlPlaneIntegrationIT.Providers.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class ControlPlaneCatalogIT {

    private static final String AGENT = "agt_catalog_scripted";
    private static final String ENVIRONMENT = "env_catalog_none";
    private static final String SESSION_MODEL = Ids.newId(IdPrefix.MODEL);
    private static final Duration WAIT = Duration.ofSeconds(30);

    private static final ScriptedOpenAiServer MODEL =
            ScriptedOpenAiServer.builder().start();
    private static final StubControlPlane CONTROL_PLANE = StubControlPlane.start();
    private static final Path DIRECTORY = writeConfiguration();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
        SpringContainerProperties.minio(registry);
        registry.add("meridian.security.service-client.control-plane-url", CONTROL_PLANE::baseUrl);
        registry.add("meridian.security.service-jwt.client-secret", () -> StubControlPlane.RUNTIME_SECRET);
        registry.add("meridian.runtime.model-routing.catalog-source", () -> "control-plane");
        // 不存在的开发目录：模型与凭据只能来自 control-plane
        registry.add(
                "meridian.runtime.model-routing.providers-directory",
                () -> DIRECTORY.resolve("missing-providers").toString());
        registry.add(
                "meridian.runtime.model-routing.budgets-directory",
                () -> DIRECTORY.resolve("budgets").toString());
        registry.add(
                "meridian.runtime.dev-agent-config.directory",
                () -> DIRECTORY.resolve("agents").toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TurnWorkerLifecycle lifecycle;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ModelCatalogPort catalog;

    @Autowired
    private SecretResolver secrets;

    private static TurnWorkerLifecycle worker;

    @BeforeEach
    void remember() {
        worker = lifecycle;
    }

    @AfterAll
    static void stopEverything() {
        if (worker != null) {
            worker.stop();
        }
        GracefulShutdownManager.getInstance().resetForTesting();
        MODEL.close();
        CONTROL_PLANE.close();
    }

    @Test
    void aSessionUsesTheCatalogAndCredentialFromControlPlaneWithoutLeakingIt(CapturedOutput output) {
        assertThat(catalog).isInstanceOf(ControlPlaneModelCatalog.class);
        assertThat(secrets).isInstanceOf(ControlPlaneSecretResolver.class);
        String tenantId = Ids.newId(IdPrefix.TENANT);
        String secretId = Ids.newId(IdPrefix.SECRET);
        String plaintext = "sk-session-" + UUID.randomUUID();
        CONTROL_PLANE.quotas.put(tenantId, 5);
        CONTROL_PLANE.secret(secretId, tenantId, null, plaintext);
        CONTROL_PLANE.model(new CatalogStub(
                SESSION_MODEL,
                tenantId,
                Ids.newId(IdPrefix.PROVIDER_ACCOUNT),
                MODEL.baseUrl(),
                secretId,
                "prb_session",
                1));
        ApiTestClient api =
                new ApiTestClient(port, CONTROL_PLANE.issue(tenantId, 600, "sessions:read", "sessions:write"));
        int resolutions = CONTROL_PLANE.secretResolutions.get();

        ApiResponse created =
                api.createSession("{\"agent\": \"" + AGENT + "\", \"environment\": \"" + ENVIRONMENT + "\"}", null);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        String sessionId = created.text("id");
        assertThat(api.sendMessage(sessionId, "#script:chat 你好").status()).isEqualTo(202);
        await().atMost(WAIT)
                .untilAsserted(
                        () -> assertThat(eventTypes(api, sessionId)).contains("agent.message", "session.status_idle"));

        assertThat(CONTROL_PLANE.secretResolutions.get()).isGreaterThan(resolutions);
        assertThat(MODEL.calls()).isNotEmpty();
        // 会话之后：日志（DEBUG）、会话事件、outbox 中都没有明文
        assertThat(output.getAll()).doesNotContain(plaintext);
        assertThat(DbAssertions.list(
                        "SELECT data::text FROM rt.session_events WHERE session_id = ?", String.class, sessionId))
                .isNotEmpty()
                .noneMatch(data -> data.contains(plaintext));
        List<String> outbox = DbAssertions.list(
                "SELECT payload::text FROM rt.outbox WHERE payload->>'tenant_id' = ?", String.class, tenantId);
        assertThat(outbox).isNotEmpty().noneMatch(payload -> payload.contains(plaintext));
        JsonNode usage = usageRecords(tenantId).getFirst();
        assertThat(usage.get("model_id").asString()).isEqualTo(SESSION_MODEL);
        assertThat(usage.get("price_book_id").asString()).isEqualTo("prb_session");
        assertThat(usage.get("price_book_version").asInt()).isEqualTo(1);
    }

    @Test
    void credentialEndpointAndPriceChangesTakeEffectImmediately() throws Exception {
        try (MockWebServer first = new MockWebServer();
                MockWebServer second = new MockWebServer()) {
            first.start();
            second.start();
            String tenantId = Ids.newId(IdPrefix.TENANT);
            String modelId = Ids.newId(IdPrefix.MODEL);
            String accountId = Ids.newId(IdPrefix.PROVIDER_ACCOUNT);
            String secretId = Ids.newId(IdPrefix.SECRET);
            CONTROL_PLANE.secret(secretId, tenantId, null, "key-v1");
            CONTROL_PLANE.model(new CatalogStub(
                    modelId, tenantId, accountId, first.url("/v1").toString(), secretId, "prb_direct", 1));
            ApiTestClient api = new ApiTestClient(port, CONTROL_PLANE.issue(tenantId, 600, "models:invoke"));

            first.enqueue(RoutedModelProviderIT.sse("scripted", "一", 10, 2, 0, 0));
            assertThat(chat(api, modelId).status()).isEqualTo(200);
            assertThat(authorization(first)).isEqualTo("Bearer key-v1");
            assertThat(usageRecords(tenantId)
                            .getLast()
                            .get("price_book_version")
                            .asInt())
                    .isEqualTo(1);

            // 凭据轮换：不等 5 分钟的明文缓存过期
            CONTROL_PLANE.secret(secretId, tenantId, null, "key-v2");
            awaitInvalidation("secret", tenantId, secretId, () -> {
                first.enqueue(RoutedModelProviderIT.sse("scripted", "二", 10, 2, 0, 0));
                assertThat(chat(api, modelId).status()).isEqualTo(200);
                assertThat(authorization(first)).isEqualTo("Bearer key-v2");
            });

            // 端点修改：不等 60 秒的目录缓存过期
            CONTROL_PLANE.model(
                    CONTROL_PLANE.model(modelId).withBaseUrl(second.url("/v1").toString()));
            awaitInvalidation("provider_account", tenantId, accountId, () -> {
                second.enqueue(RoutedModelProviderIT.sse("scripted", "三", 10, 2, 0, 0));
                assertThat(chat(api, modelId).status()).isEqualTo(200);
                assertThat(authorization(second)).isEqualTo("Bearer key-v2");
            });

            // 价格表新版本：之后的用量记录带新版本，已有记录不变
            int before = usageRecords(tenantId).size();
            CONTROL_PLANE.model(CONTROL_PLANE.model(modelId).withPriceBookVersion(2));
            awaitInvalidation("price_book", tenantId, "prb_direct", () -> {
                second.enqueue(RoutedModelProviderIT.sse("scripted", "四", 10, 2, 0, 0));
                assertThat(chat(api, modelId).status()).isEqualTo(200);
                assertThat(usageRecords(tenantId)
                                .getLast()
                                .get("price_book_version")
                                .asInt())
                        .isEqualTo(2);
            });
            List<JsonNode> records = usageRecords(tenantId);
            assertThat(records.subList(0, before))
                    .allSatisfy(record ->
                            assertThat(record.get("price_book_version").asInt()).isEqualTo(1));

            // 模型删除：立即 404
            CONTROL_PLANE.removeModel(modelId);
            awaitInvalidation("model", tenantId, modelId, () -> {
                ApiResponse gone = chat(api, modelId);
                assertThat(gone.status()).isEqualTo(404);
                assertThat(gone.text("code")).isEqualTo("model.not_found");
            });
        }
    }

    @Test
    void modelsOfAnotherTenantAreNotFound() throws Exception {
        try (MockWebServer provider = new MockWebServer()) {
            provider.start();
            String owner = Ids.newId(IdPrefix.TENANT);
            String modelId = Ids.newId(IdPrefix.MODEL);
            CONTROL_PLANE.model(new CatalogStub(
                    modelId,
                    owner,
                    Ids.newId(IdPrefix.PROVIDER_ACCOUNT),
                    provider.url("/v1").toString(),
                    null,
                    null,
                    null));
            ApiTestClient other =
                    new ApiTestClient(port, CONTROL_PLANE.issue(Ids.newId(IdPrefix.TENANT), 600, "models:invoke"));

            ApiResponse response = chat(other, modelId);

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.text("code")).isEqualTo("model.not_found");
            assertThat(provider.getRequestCount()).isZero();
        }
    }

    /** 发布一条 {@code config.invalidate}，直到断言通过（订阅在监听容器中异步生效，先发的消息可能没人收到）。 */
    private void awaitInvalidation(String type, String tenantId, String id, ThrowingRunnable assertion) {
        String message = "{\"type\":\"" + type + "\",\"tenant_id\":\"" + tenantId + "\",\"id\":\"" + id + "\"}";
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    redis.convertAndSend(CatalogInvalidationListener.CHANNEL, message);
                    Thread.sleep(50);
                    assertion.run();
                });
    }

    private static ApiResponse chat(ApiTestClient api, String modelId) {
        return api.call(
                "POST /v1/chat/completions",
                HttpMethod.POST,
                "/v1/chat/completions",
                "{\"model\": \"" + modelId + "\", \"messages\": [{\"role\": \"user\", \"content\": \"你好\"}]}",
                null);
    }

    private static String authorization(MockWebServer server) throws InterruptedException {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        return request.getHeaders().get("Authorization");
    }

    private static List<String> eventTypes(ApiTestClient api, String sessionId) {
        return api.listEvents(sessionId, "limit=100")
                .body()
                .get("data")
                .valueStream()
                .map(event -> event.get("type").asString())
                .toList();
    }

    private static List<JsonNode> usageRecords(String tenantId) {
        return DbAssertions.list(
                        "SELECT payload::text FROM rt.outbox WHERE topic = 'usage' AND payload->>'tenant_id' = ? ORDER BY id",
                        String.class,
                        tenantId)
                .stream()
                .map(payload -> JsonMappers.lenient().readTree(payload))
                .toList();
    }

    private static Path writeConfiguration() {
        try {
            Path directory = Files.createTempDirectory("control-plane-catalog-it");
            Path agents = Files.createDirectories(directory.resolve("agents"));
            Files.createDirectories(directory.resolve("budgets"));
            Files.writeString(agents.resolve("agent.yaml"), """
                    kind: agent_version
                    agent_id: %s
                    agent_version_id: agv_catalog_scripted_1
                    name: scripted
                    model:
                      model_id: %s
                    system_prompt: 你是测试智能体。
                    tools: []
                    mcp_servers: []
                    skills: []
                    subagents: []
                    permissions:
                      mode: default
                      rules: []
                    compaction:
                      reserved_tokens: 20000
                    plan_mode: false
                    knowledge:
                      knowledge_base_ids: []
                      prefetch: false
                      prefetch_top_k: 5
                    max_iters: 10
                    expose_thinking: false
                    """.formatted(AGENT, SESSION_MODEL));
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

    /** 可以抛出受检异常的断言。 */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
