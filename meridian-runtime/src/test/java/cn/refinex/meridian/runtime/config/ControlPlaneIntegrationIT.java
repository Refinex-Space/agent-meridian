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
import cn.refinex.meridian.security.apikey.ControlPlaneApiKeyResolver;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import cn.refinex.meridian.testsupport.auth.KeycloakRealm;
import cn.refinex.meridian.testsupport.model.ScriptedOpenAiServer;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * {@code max_running}（A07）。T14b（ADR-0044）：这些内部调用都带 runtime 换取的服务 JWT（A02）；Keycloak 登录的控制台用户带
 * {@code Meridian-Project} 调用公开 API，按 control-plane 给出的项目作用域授权（A01，默认的 {@code sub} 声明）。control-plane 由
 * {@link StubControlPlane} 模拟（control-plane 一侧由它自己的集成测试覆盖），Redis、PostgreSQL、Keycloak 与 Worker 都是真实的，模型是
 * 脚本化模型服务。
 */
@SpringBootTest(
        classes = {RuntimeApplication.class, ControlPlaneIntegrationIT.Providers.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class ControlPlaneIntegrationIT {

    private static final String AGENT = "agt_cp_scripted";
    private static final String ENVIRONMENT = "env_cp_none";
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
        // 控制台用户（ADR-0044）：Keycloak 开发 realm，默认的 sub 声明
        registry.add("meridian.security.oidc.issuer", KeycloakRealm::issuer);
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

    @Test
    void internalCallsCarryTheServiceJwtOfRuntime() {
        String tenantId = Ids.newId(IdPrefix.TENANT);
        String key = CONTROL_PLANE.issue(tenantId, 600, "sessions:read");
        int before = CONTROL_PLANE.callers.size();

        assertThat(new ApiTestClient(port, key).listSessions(null).status()).isEqualTo(200);

        // 替身只在服务 JWT 的签名、签发方、aud=control-plane 与 sub=runtime 都正确时应答
        assertThat(CONTROL_PLANE.callers.subList(before, CONTROL_PLANE.callers.size()))
                .isNotEmpty()
                .containsOnly(ServiceJwts.RUNTIME);
    }

    @Test
    void consoleUsersCallThePublicApiWithTheirProjectRole() {
        String tenantId = Ids.newId(IdPrefix.TENANT);
        String projectId = Ids.newId(IdPrefix.PROJECT);
        String otherProject = Ids.newId(IdPrefix.PROJECT);
        CONTROL_PLANE.quotas.put(tenantId, 5);
        String developerSub = KeycloakRealm.user("runtime-developer");
        String viewerSub = KeycloakRealm.user("runtime-viewer");
        CONTROL_PLANE.grant(developerSub, tenantId, projectId, "sessions:read", "sessions:write", "models:invoke");
        CONTROL_PLANE.grant(viewerSub, tenantId, projectId, "sessions:read");
        CONTROL_PLANE.grant(developerSub, tenantId, otherProject);
        String developerToken = KeycloakRealm.token("runtime-developer");
        ApiTestClient developer = new ApiTestClient(port, developerToken, Map.of("Meridian-Project", projectId));
        ApiTestClient viewer =
                new ApiTestClient(port, KeycloakRealm.token("runtime-viewer"), Map.of("Meridian-Project", projectId));

        ApiResponse created = developer.createSession(
                "{\"agent\": \"" + AGENT + "\", \"environment\": \"" + ENVIRONMENT + "\"}", null);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        assertThat(developer
                        .listSessions(null)
                        .body()
                        .get("data")
                        .valueStream()
                        .map(session -> session.get("id").asString()))
                .contains(created.text("id"));
        // 观察者能读同一项目的会话，不能写
        assertThat(viewer.listSessions(null).status()).isEqualTo(200);
        ApiResponse viewerWrite =
                viewer.createSession("{\"agent\": \"" + AGENT + "\", \"environment\": \"" + ENVIRONMENT + "\"}", null);
        assertThat(viewerWrite.status()).isEqualTo(403);
        assertThat(viewerWrite.text("code")).isEqualTo("auth.insufficient_scope");
        // 用户的请求按用户的桶限流
        assertThat(developer.listSessions(null).headers().getFirst("RateLimit-Limit"))
                .isEqualTo("600");
        assertThat(redis.hasKey("ratelimit:user:{" + developerSub + "}")).isTrue();

        // 在该项目没有角色、项目不存在，都是 403
        for (String project : new String[] {otherProject, Ids.newId(IdPrefix.PROJECT)}) {
            ApiResponse denied =
                    new ApiTestClient(port, developerToken, Map.of("Meridian-Project", project)).listSessions(null);
            assertThat(denied.status()).isEqualTo(403);
            assertThat(denied.text("code")).isEqualTo("access.forbidden");
        }
        // 缺少 Meridian-Project
        ApiResponse missing = new ApiTestClient(port, null)
                .callInvalid(
                        "GET /v1/sessions",
                        HttpMethod.GET,
                        "/v1/sessions",
                        null,
                        Map.of("Authorization", "Bearer " + developerToken));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.text("code")).isEqualTo("auth.project_required");
        // 签名无效的令牌
        ApiResponse forged = new ApiTestClient(
                        port,
                        developerToken.substring(0, developerToken.length() - 4) + "AAAA",
                        Map.of("Meridian-Project", projectId))
                .listSessions(null);
        assertThat(forged.status()).isEqualTo(401);
        assertThat(forged.text("code")).isEqualTo("auth.invalid_console_token");
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
}
