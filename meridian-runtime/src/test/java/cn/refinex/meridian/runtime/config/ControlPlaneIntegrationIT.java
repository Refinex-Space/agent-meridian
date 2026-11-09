package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.agentscope.assembly.SkillRepositoryProvider;
import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import cn.refinex.meridian.engine.agentscope.assembly.WorkspaceFilesystemProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.InMemoryWorkspaceFilesystems;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticSkillRepository;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticToolProvider;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.dispatch.app.TenantQuotaListener;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.security.apikey.ApiKeyHashes;
import cn.refinex.meridian.security.apikey.ControlPlaneApiKeyResolver;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import cn.refinex.meridian.testsupport.model.ScriptedOpenAiServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * runtime 与 control-plane 的对接（T14a，ADR-0042）：API Key 经 control-plane 内部接口校验（缓存 60 秒、{@code key.revoked} 后 1 秒内
 * 拒绝，A03）、按 Key 限流（A04）、新租户不手工插入数据就能完成一轮会话，配额变更经 {@code config.invalidate} 更新
 * {@code max_running}（A07）。control-plane 由 JDK 的 HTTP 服务模拟它的两个内部接口（control-plane 一侧由它自己的集成测试覆盖），
 * Redis、PostgreSQL 与 Worker 都是真实的，模型是脚本化模型服务。
 */
@SpringBootTest(
        classes = {RuntimeApplication.class, ControlPlaneIntegrationIT.Providers.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class ControlPlaneIntegrationIT {

    private static final String AGENT = "agt_cp_scripted";
    private static final String ENVIRONMENT = "env_cp_none";
    private static final String SERVICE_CREDENTIAL = "svc_test_out";
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
        registry.add("meridian.security.service-client.credential", () -> SERVICE_CREDENTIAL);
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

    @Autowired
    private StringRedisTemplate redis;

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
    void aNewTenantCompletesATurnAndQuotaChangesReachMaxRunning() {
        String tenantId = Ids.newId(IdPrefix.TENANT);
        String key = CONTROL_PLANE.issue(tenantId, 600, "sessions:read", "sessions:write");
        CONTROL_PLANE.quotas.put(tenantId, 3);
        ApiTestClient api = new ApiTestClient(port, key);

        ApiResponse created =
                api.createSession("{\"agent\": \"" + AGENT + "\", \"environment\": \"" + ENVIRONMENT + "\"}", null);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        String sessionId = created.text("id");
        assertThat(api.sendMessage(sessionId, "#script:chat 你好").status()).isEqualTo(202);

        await().atMost(WAIT).untilAsserted(() -> {
            ApiResponse events = api.listEvents(sessionId, "limit=100");
            List<String> types = events.body()
                    .get("data")
                    .valueStream()
                    .map(event -> event.get("type").asString())
                    .toList();
            assertThat(types).contains("agent.message", "session.status_idle");
        });
        // 计数行由入队自动创建，max_running 取自 control-plane 的配额（不手工插入任何数据）
        assertThat(maxRunning(tenantId)).isEqualTo(3);
        assertThat(CONTROL_PLANE.quotaCalls.get()).isEqualTo(1);

        CONTROL_PLANE.quotas.put(tenantId, 9);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            redis.convertAndSend(
                    TenantQuotaListener.CHANNEL, "{\"type\":\"tenant_quota\",\"tenant_id\":\"" + tenantId + "\"}");
            assertThat(maxRunning(tenantId)).isEqualTo(9);
        });
    }

    @Test
    void aRevokedKeyIsRejectedWithinOneSecond() {
        String tenantId = Ids.newId(IdPrefix.TENANT);
        String key = CONTROL_PLANE.issue(tenantId, 600, "sessions:read");
        ApiTestClient api = new ApiTestClient(port, key);
        assertThat(api.listSessions(null).status()).isEqualTo(200);
        int verifications = CONTROL_PLANE.verifyCalls.get();

        CONTROL_PLANE.revoke(key);
        // 吊销通知到达之前，缓存仍然接受：证明下面的拒绝来自通知，而不是缓存未命中
        assertThat(api.listSessions(null).status()).isEqualTo(200);
        assertThat(CONTROL_PLANE.verifyCalls.get()).isEqualTo(verifications);

        long published = System.nanoTime();
        await().atMost(Duration.ofSeconds(1))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    redis.convertAndSend(ControlPlaneApiKeyResolver.REVOKED_CHANNEL, CONTROL_PLANE.keyId(key));
                    ApiResponse rejected = api.listSessions(null);
                    assertThat(rejected.status()).isEqualTo(401);
                    assertThat(rejected.text("code")).isEqualTo("auth.invalid_api_key");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - published)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void requestsBeyondTheKeysRateAreRejectedWithRetryAfter() {
        String tenantId = Ids.newId(IdPrefix.TENANT);
        String key = CONTROL_PLANE.issue(tenantId, 3, "sessions:read");
        ApiTestClient api = new ApiTestClient(port, key);

        for (int i = 1; i <= 3; i++) {
            ApiResponse ok = api.listSessions(null);
            assertThat(ok.status()).isEqualTo(200);
            assertThat(ok.headers().getFirst("RateLimit-Limit")).isEqualTo("3");
            assertThat(ok.headers().getFirst("RateLimit-Remaining")).isEqualTo(Integer.toString(3 - i));
            assertThat(Long.parseLong(ok.headers().getFirst("RateLimit-Reset"))).isBetween(1L, 60L);
        }
        ApiResponse limited = api.call("GET /v1/sessions", HttpMethod.GET, "/v1/sessions", null, null);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.text("code")).isEqualTo("rate_limit.exceeded");
        assertThat(Long.parseLong(limited.headers().getFirst("Retry-After"))).isBetween(1L, 20L);
        assertThat(limited.headers().getFirst("RateLimit-Remaining")).isEqualTo("0");
    }

    private static int maxRunning(String tenantId) {
        return ((Number) DbAssertions.row("SELECT max_running FROM rt.tenant_concurrency WHERE tenant_id = ?", tenantId)
                        .get("max_running"))
                .intValue();
    }

    private static Path writeConfiguration() {
        try {
            Path directory = Files.createTempDirectory("control-plane-integration-it");
            Path agents = Files.createDirectories(directory.resolve("agents"));
            Path providers = Files.createDirectories(directory.resolve("providers"));
            Files.createDirectories(directory.resolve("budgets"));
            Files.writeString(providers.resolve("scripted.yaml"), """
                    kind: provider_account
                    provider_account_id: prv_cp_scripted
                    provider: openai_compatible
                    base_url: %s
                    models:
                      - model_id: mdl_cp_scripted
                        upstream_model: scripted
                        context_window: 131072
                        max_output_tokens: 1024
                    """.formatted(MODEL.baseUrl()));
            Files.writeString(agents.resolve("agent.yaml"), """
                    kind: agent_version
                    agent_id: %s
                    agent_version_id: agv_cp_scripted_1
                    name: scripted
                    model:
                      model_id: mdl_cp_scripted
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

    /** 本测试不需要平台工具，给出空的提供者，让生产装配的引擎可以启动。 */
    @TestConfiguration
    static class Providers {

        @Bean
        ToolProvider toolProvider() {
            return StaticToolProvider.always();
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

    /**
     * control-plane 的两个内部接口：按哈希校验 API Key、读取租户配额。只接受本测试的服务凭据，响应格式与 {@code meridian-internal-v1.yaml}
     * 一致。
     */
    static final class StubControlPlane implements AutoCloseable {

        final Map<String, Integer> quotas = new ConcurrentHashMap<>();
        final AtomicInteger verifyCalls = new AtomicInteger();
        final AtomicInteger quotaCalls = new AtomicInteger();
        private final Map<String, String> verifications = new ConcurrentHashMap<>();
        private final Map<String, String> keyIds = new ConcurrentHashMap<>();
        private final HttpServer server;

        private StubControlPlane(HttpServer server) {
            this.server = server;
        }

        static StubControlPlane start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                StubControlPlane stub = new StubControlPlane(server);
                server.createContext("/internal/v1/api-keys:verify", stub::verify);
                server.createContext("/internal/v1/tenants/", stub::quota);
                server.start();
                return stub;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String issue(String tenantId, int ratePerMinute, String... scopes) {
            String plaintext = "mk_live_" + Ids.newUlid() + "abcdefghijklmn";
            String keyId = Ids.newId(IdPrefix.API_KEY);
            String scopeJson = String.join(
                    ",",
                    java.util.Arrays.stream(scopes).map(s -> "\"" + s + "\"").toList());
            verifications.put(ApiKeyHashes.sha256(plaintext), """
                    {"api_key_id":"%s","tenant_id":"%s","project_id":"%s","scopes":[%s],"rate_limit_per_minute":%d,
                     "expires_at":null}
                    """.formatted(
                            keyId, tenantId, Ids.newId(IdPrefix.PROJECT), scopeJson, ratePerMinute));
            keyIds.put(plaintext, keyId);
            return plaintext;
        }

        String keyId(String plaintext) {
            return keyIds.get(plaintext);
        }

        void revoke(String plaintext) {
            verifications.remove(ApiKeyHashes.sha256(plaintext));
        }

        private void verify(HttpExchange exchange) throws IOException {
            verifyCalls.incrementAndGet();
            if (!authorized(exchange)) {
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String hash = body.replaceAll(".*\"key_hash\"\\s*:\\s*\"([0-9a-f]{64})\".*", "$1");
            String verification = verifications.get(hash);
            if (verification == null) {
                respond(exchange, 404, problem("api_key.not_found", 404));
            } else {
                respond(exchange, 200, verification);
            }
        }

        private void quota(HttpExchange exchange) throws IOException {
            quotaCalls.incrementAndGet();
            if (!authorized(exchange)) {
                return;
            }
            String tenantId = exchange.getRequestURI().getPath().split("/")[4];
            Integer quota = quotas.get(tenantId);
            if (quota == null) {
                respond(exchange, 404, problem("tenant.not_found", 404));
            } else {
                respond(exchange, 200, "{\"tenant_id\":\"" + tenantId + "\",\"max_concurrent_turns\":" + quota + "}");
            }
        }

        private boolean authorized(HttpExchange exchange) throws IOException {
            if (("Bearer " + SERVICE_CREDENTIAL)
                    .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                return true;
            }
            respond(exchange, 401, problem("auth.invalid_service_credential", 401));
            return false;
        }

        private static String problem(String code, int status) {
            return "{\"type\":\"urn:meridian:error:" + code + "\",\"title\":\"x\",\"status\":" + status
                    + ",\"detail\":null,\"instance\":\"/\",\"code\":\"" + code + "\",\"request_id\":\"r\"}";
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders()
                    .set("Content-Type", status == 200 ? "application/json" : "application/problem+json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
