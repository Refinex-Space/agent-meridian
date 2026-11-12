package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.spec.CompactionSpec;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.KnowledgeSpec;
import cn.refinex.meridian.engine.spi.spec.McpServerSpec;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.engine.spi.spec.SkillRef;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.approval.app.PolicyStorePort;
import cn.refinex.meridian.runtime.approval.infra.ControlPlanePolicyStore;
import cn.refinex.meridian.runtime.config.StubControlPlane.CatalogStub;
import cn.refinex.meridian.runtime.session.app.AgentConfigProvider;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.session.infra.ControlPlaneAgentConfigProvider;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import cn.refinex.meridian.testsupport.mcp.TestMcpServer;
import cn.refinex.meridian.testsupport.model.ScriptedOpenAiServer;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * runtime 经 control-plane 读取智能体配置（T15b-A04、A05、A06、A07、A08 的 runtime 部分，ADR-0047）：{@code agent-config.source} 与
 * {@code catalog-source} 都是 {@code control-plane}，开发配置目录都指向不存在的路径（不读 {@code dev-config/}）；生产装配的内核、平台工具、
 * MCP 连接器与技能仓库。control-plane 由 {@link StubControlPlane} 按内部接口规范模拟（发布、校验、快照由 control-plane 的集成测试覆盖）。
 *
 * <p>缓存时间设为 10 分钟：配置变化在测试中立即生效，只能是 {@code config.invalidate} 的作用。
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class AgentConfigSessionIT {

    private static final Duration WAIT = Duration.ofSeconds(40);
    private static final String MCP_TOKEN = "runtime-mcp-token-T15b";
    private static final Path DOCX = Path.of("..", "skills", "builtin", "docx");

    private static final ScriptedOpenAiServer MODEL = ScriptedOpenAiServer.builder()
            .script("""
                    name: write-note
                    description: 写一个文件后回答
                    steps:
                      - tool_calls:
                          - name: write_file
                            arguments: {path: notes/a.md, content: 记录}
                      - text: 已写好。
                    """)
            .script("""
                    name: mcp-lookup
                    description: 调用 MCP 工具查订单后回答
                    steps:
                      - tool_calls:
                          - name: mcp__crm__lookup_order
                            arguments: {order_id: o-42}
                      - text: 订单查到了。
                    """)
            .script("""
                    name: web-search
                    description: 联网搜索后回答
                    steps:
                      - tool_calls:
                          - name: web_search
                            arguments: {query: 天气}
                      - text: 搜到了。
                    """)
            .script("""
                    name: load-docx
                    description: 加载 docx 技能的正文后回答
                    steps:
                      - tool_calls:
                          - name: load_skill_through_path
                            arguments: {skillId: docx_meridian, path: SKILL.md}
                      - text: 技能已加载。
                    """)
            .start();
    private static final StubControlPlane CONTROL_PLANE = StubControlPlane.start();
    private static final TestMcpServer MCP = TestMcpServer.start(MCP_TOKEN);
    private static final Path DIRECTORY = directory();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
        SpringContainerProperties.minio(registry);
        registry.add("meridian.security.service-client.control-plane-url", CONTROL_PLANE::baseUrl);
        registry.add("meridian.security.service-jwt.client-secret", () -> StubControlPlane.RUNTIME_SECRET);
        registry.add("meridian.runtime.agent-config.source", () -> "control-plane");
        registry.add("meridian.runtime.agent-config.cache-ttl", () -> "10m");
        registry.add("meridian.runtime.model-routing.catalog-source", () -> "control-plane");
        // 不存在的开发目录：智能体、环境、技能、供应商都只能来自 control-plane
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
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TurnWorkerLifecycle lifecycle;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private AgentConfigProvider configs;

    @Autowired
    private PolicyStorePort policies;

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
        MCP.close();
    }

    @Test
    void newSessionsUseANewlyPublishedVersionAndRevisionImmediatelyWhileRunningSessionsKeepTheirs() {
        assertThat(configs).isInstanceOf(ControlPlaneAgentConfigProvider.class);
        assertThat(policies).isInstanceOf(ControlPlanePolicyStore.class);
        Tenant tenant = tenant();
        String agentId = Ids.newId(IdPrefix.AGENT);
        String v1 = Ids.newId(IdPrefix.AGENT_VERSION);
        String v2 = Ids.newId(IdPrefix.AGENT_VERSION);
        String environmentId = Ids.newId(IdPrefix.ENVIRONMENT);
        CONTROL_PLANE.publish(
                tenant.id(),
                tenant.project(),
                agent(agentId, v1, tenant.model(), "版本一的提示词", Set.of(), PermissionPolicy.empty()));
        CONTROL_PLANE.environment(tenant.id(), tenant.project(), noneEnvironment(environmentId, 1));

        String running = createSession(tenant.api(), agentId, environmentId);
        assertThat(tenant.api().getSession(running).text("agent_version_id")).isEqualTo(v1);
        converse(tenant.api(), running, "#script:chat 你好");
        assertThat(lastBodyContaining("版本一的提示词")).isTrue();

        CONTROL_PLANE.publish(
                tenant.id(),
                tenant.project(),
                agent(agentId, v2, tenant.model(), "版本二的提示词", Set.of(), PermissionPolicy.empty()));
        CONTROL_PLANE.environment(tenant.id(), tenant.project(), noneEnvironment(environmentId, 2));
        // 缓存 10 分钟：没有通知时新会话仍用缓存中的版本
        String cached = createSession(tenant.api(), agentId, environmentId);
        assertThat(tenant.api().getSession(cached).text("agent_version_id")).isEqualTo(v1);

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    invalidate("agent", tenant.id(), agentId);
                    invalidate("environment", tenant.id(), environmentId);
                    ApiResponse fresh = tenant.api().getSession(createSession(tenant.api(), agentId, environmentId));
                    assertThat(fresh.text("agent_version_id")).isEqualTo(v2);
                    assertThat(fresh.body().get("environment_revision").asLong())
                            .isEqualTo(2);
                });
        String fresh = createSession(tenant.api(), agentId, environmentId);
        converse(tenant.api(), fresh, "#script:chat 新会话");
        assertThat(lastBodyContaining("版本二的提示词")).isTrue();

        // 运行中的会话继续使用创建时绑定的版本与修订
        converse(tenant.api(), running, "#script:chat 继续");
        assertThat(lastBodyContaining("版本一的提示词")).isTrue();
        ApiResponse session = tenant.api().getSession(running);
        assertThat(session.text("agent_version_id")).isEqualTo(v1);
        assertThat(session.body().get("environment_revision").asLong()).isEqualTo(1);
    }

    @Test
    void projectPoliciesAndRememberedDecisionsLiveInControlPlaneAndTakePartInSynthesis() {
        Tenant tenant = tenant();
        String agentId = Ids.newId(IdPrefix.AGENT);
        String environmentId = Ids.newId(IdPrefix.ENVIRONMENT);
        // 智能体没有放行 write_file：DEFAULT 模式下要确认
        CONTROL_PLANE.publish(
                tenant.id(),
                tenant.project(),
                agent(
                        agentId,
                        Ids.newId(IdPrefix.AGENT_VERSION),
                        tenant.model(),
                        "你会写文件。",
                        Set.of("write_file"),
                        PermissionPolicy.empty()));
        CONTROL_PLANE.environment(tenant.id(), tenant.project(), noneEnvironment(environmentId, 1));
        CONTROL_PLANE.policies(tenant.id(), tenant.project(), List.of(), List.of());

        String first = createSession(tenant.api(), agentId, environmentId);
        assertThat(tenant.api().sendMessage(first, "#script:write-note").status())
                .isEqualTo(202);
        await().atMost(WAIT)
                .untilAsserted(
                        () -> assertThat(eventTypes(tenant.api(), first)).contains("session.status_requires_action"));
        String actionId = tenant.api()
                .listPendingActions(first, "")
                .body()
                .at("/data/0/id")
                .asString();
        ApiResponse approved = tenant.api().sendEvents(first, """
                {"events": [{"type": "user.tool_confirmation", "action_id": "%s", "approved": true,
                             "remember_scope": "project"}]}""".formatted(actionId), null);
        assertThat(approved.status()).as(String.valueOf(approved.body())).isEqualTo(202);
        awaitIdleWith(tenant.api(), first, "已写好。");
        assertThat(CONTROL_PLANE.rememberedRules)
                .anySatisfy(rule ->
                        assertThat(rule).contains("\"tool_name\":\"write_file\"", "\"behavior\":\"allow\"", actionId));

        // 项目级规则存于 control-plane：同项目的另一个会话不再确认
        String second = createSession(tenant.api(), agentId, environmentId);
        converse(tenant.api(), second, "#script:write-note");
        assertThat(eventTypes(tenant.api(), second)).doesNotContain("session.status_requires_action");
        assertThat(toolResult(tenant.api(), second).path("permission").asString())
                .isEqualTo("allowed");

        // 租户策略的 deny 经通知立即生效，压过项目策略的 allow
        CONTROL_PLANE.policies(
                tenant.id(),
                tenant.project(),
                List.<String[]>of(new String[] {"write_file", null, "deny"}),
                List.<String[]>of(new String[] {"write_file", null, "allow"}));
        invalidate("permission_policy", tenant.id(), tenant.id());
        await().atMost(WAIT).untilAsserted(() -> {
            String third = createSession(tenant.api(), agentId, environmentId);
            converse(tenant.api(), third, "#script:write-note");
            assertThat(toolResult(tenant.api(), third).path("permission").asString())
                    .isEqualTo("denied");
        });
    }

    @Test
    void mcpToolsOfAPublishedVersionAppearInTheSessionWithTheCredentialFromTheSecretStore() {
        Tenant tenant = tenant();
        String agentId = Ids.newId(IdPrefix.AGENT);
        String environmentId = Ids.newId(IdPrefix.ENVIRONMENT);
        String secretId = Ids.newId(IdPrefix.SECRET);
        CONTROL_PLANE.secret(secretId, tenant.id(), tenant.project(), "Bearer " + MCP_TOKEN);
        McpServerSpec crm = new McpServerSpec(
                Ids.newId(IdPrefix.MCP_SERVER),
                "crm",
                MCP.streamableUrl(),
                McpServerSpec.McpTransport.STREAMABLE_HTTP,
                Map.of("Authorization", secretId),
                SideEffect.NON_IDEMPOTENT,
                Map.of("lookup_order", SideEffect.NONE),
                Set.of("lookup_order"));
        ResolvedAgentSpec spec = new ResolvedAgentSpec(
                agentId,
                Ids.newId(IdPrefix.AGENT_VERSION),
                "mcp-agent",
                ModelSpec.of(tenant.model()),
                "你会查订单。",
                Set.of(),
                List.of(crm),
                List.of(),
                List.of(),
                allow("mcp__crm__lookup_order"),
                CompactionSpec.defaults(),
                false,
                KnowledgeSpec.none(),
                10,
                false);
        CONTROL_PLANE.publish(tenant.id(), tenant.project(), spec);
        CONTROL_PLANE.environment(tenant.id(), tenant.project(), noneEnvironment(environmentId, 1));
        int resolutions = CONTROL_PLANE.secretResolutions.get();
        int requests = MCP.requests().size();

        String session = createSession(tenant.api(), agentId, environmentId);
        converse(tenant.api(), session, "#script:mcp-lookup");

        JsonNode use = event(tenant.api(), session, "agent.mcp_tool_use");
        assertThat(use.path("name").asString()).isEqualTo("mcp__crm__lookup_order");
        assertThat(event(tenant.api(), session, "agent.mcp_tool_result").toString())
                .contains(TestMcpServer.LOOKUP_RESULT + "o-42");
        assertThat(CONTROL_PLANE.secretResolutions.get()).as("凭据经凭据库解析").isGreaterThan(resolutions);
        assertThat(MCP.requests().subList(requests, MCP.requests().size()))
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer " + MCP_TOKEN));
    }

    @Test
    void tenantPromptAndWebSearchBackendFollowTenantSettingsIncludingForRunningSessions() throws Exception {
        try (MockWebServer first = new MockWebServer();
                MockWebServer second = new MockWebServer()) {
            first.start();
            second.start();
            Tenant tenant = tenant();
            String agentId = Ids.newId(IdPrefix.AGENT);
            String environmentId = Ids.newId(IdPrefix.ENVIRONMENT);
            CONTROL_PLANE.publish(
                    tenant.id(),
                    tenant.project(),
                    agent(
                            agentId,
                            Ids.newId(IdPrefix.AGENT_VERSION),
                            tenant.model(),
                            "你会联网搜索。",
                            Set.of("web_search"),
                            allow("web_search")));
            CONTROL_PLANE.environment(tenant.id(), tenant.project(), noneEnvironment(environmentId, 1));
            CONTROL_PLANE.tenantSettings(tenant.id(), "租户提示词一：回答使用简体中文。", searxng(first));
            first.enqueue(searchResult("来自后端甲"));
            second.enqueue(searchResult("来自后端乙"));

            String session = createSession(tenant.api(), agentId, environmentId);
            converse(tenant.api(), session, "#script:web-search");
            assertThat(lastBodyContaining("租户提示词一：回答使用简体中文。")).isTrue();
            assertThat(path(first)).startsWith("/search?q=");
            assertThat(toolResult(tenant.api(), session).toString()).contains("来自后端甲");

            CONTROL_PLANE.tenantSettings(tenant.id(), "租户提示词二：回答使用英文。", searxng(second));
            invalidate("tenant_settings", tenant.id(), tenant.id());
            // 同一个会话的下一轮：租户提示词随实例缓存，通知时淘汰了该租户的实例（D32）
            converse(tenant.api(), session, "#script:web-search");
            assertThat(lastBodyContaining("租户提示词二：回答使用英文。")).isTrue();
            assertThat(path(second)).startsWith("/search?q=");
            assertThat(first.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void theBuiltinDocxSkillFromControlPlaneIsListedAndLoadsInASession() throws IOException {
        Tenant tenant = tenant();
        String agentId = Ids.newId(IdPrefix.AGENT);
        String environmentId = Ids.newId(IdPrefix.ENVIRONMENT);
        String skillId = Ids.newId(IdPrefix.SKILL);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", Files.readString(DOCX.resolve("SKILL.md"), StandardCharsets.UTF_8));
        files.put(
                "scripts/md_to_docx.py",
                Files.readString(DOCX.resolve("scripts/md_to_docx.py"), StandardCharsets.UTF_8));
        ResolvedAgentSpec spec = new ResolvedAgentSpec(
                agentId,
                Ids.newId(IdPrefix.AGENT_VERSION),
                "docx-agent",
                ModelSpec.of(tenant.model()),
                "你会写 Word 文档。",
                Set.of(),
                List.of(),
                List.of(new SkillRef(skillId, 1, "docx")),
                List.of(),
                allow("load_skill_through_path"),
                CompactionSpec.defaults(),
                false,
                KnowledgeSpec.none(),
                10,
                false);
        CONTROL_PLANE.publish(tenant.id(), tenant.project(), spec, new SkillDefinition(skillId, 1, "docx", files));
        CONTROL_PLANE.environment(tenant.id(), tenant.project(), noneEnvironment(environmentId, 1));

        String session = createSession(tenant.api(), agentId, environmentId);
        converse(tenant.api(), session, "#script:load-docx");

        assertThat(lastBodyContaining("Turn Markdown into a Word (.docx) document"))
                .as("技能清单在系统提示词中")
                .isTrue();
        assertThat(toolResult(tenant.api(), session).toString()).contains("Markdown to Word");
    }

    /** 一个新租户：项目、带两种会话作用域的 Key、目录中指向脚本化模型服务的模型。 */
    private Tenant tenant() {
        String tenantId = Ids.newId(IdPrefix.TENANT);
        String projectId = Ids.newId(IdPrefix.PROJECT);
        String modelId = Ids.newId(IdPrefix.MODEL);
        CONTROL_PLANE.quotas.put(tenantId, 5);
        CONTROL_PLANE.model(new CatalogStub(
                modelId, tenantId, Ids.newId(IdPrefix.PROVIDER_ACCOUNT), MODEL.baseUrl(), null, null, null));
        ApiTestClient api = new ApiTestClient(
                port, CONTROL_PLANE.issue(tenantId, projectId, 600, "sessions:read", "sessions:write"));
        return new Tenant(tenantId, projectId, modelId, api);
    }

    private static ResolvedAgentSpec agent(
            String agentId,
            String versionId,
            String modelId,
            String prompt,
            Set<String> tools,
            PermissionPolicy policy) {
        return new ResolvedAgentSpec(
                agentId,
                versionId,
                "config-agent",
                ModelSpec.of(modelId),
                prompt,
                tools,
                List.of(),
                List.of(),
                List.of(),
                policy,
                CompactionSpec.defaults(),
                false,
                KnowledgeSpec.none(),
                10,
                false);
    }

    private static PermissionPolicy allow(String tool) {
        return new PermissionPolicy(
                PermissionMode.DEFAULT,
                List.of(new PermissionRule(tool, null, PermissionBehavior.ALLOW, PolicyLayer.AGENT_VERSION)));
    }

    private static EnvironmentSpec noneEnvironment(String environmentId, long revision) {
        return new EnvironmentSpec(environmentId, revision, EnvironmentSpec.Tier.NONE, null, List.of());
    }

    private static Map<String, Object> searxng(MockWebServer server) {
        Map<String, Object> webSearch = new LinkedHashMap<>();
        webSearch.put("backend", "searxng");
        webSearch.put("base_url", server.url("/").toString().replaceAll("/$", ""));
        webSearch.put("secret_id", null);
        return webSearch;
    }

    private static MockResponse searchResult(String content) {
        return new MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("{\"results\":[{\"title\":\"结果\",\"url\":\"https://example.com/r\",\"content\":\"" + content
                        + "\"}]}")
                .build();
    }

    private static String path(MockWebServer server) throws InterruptedException {
        RecordedRequest request = server.takeRequest(10, TimeUnit.SECONDS);
        assertThat(request).as("搜索后端收到请求").isNotNull();
        return request.getTarget();
    }

    private void invalidate(String type, String tenantId, String id) {
        redis.convertAndSend(
                CatalogInvalidationListener.CHANNEL,
                "{\"type\":\"" + type + "\",\"tenant_id\":\"" + tenantId + "\",\"id\":\"" + id + "\"}");
    }

    private static String createSession(ApiTestClient api, String agentId, String environmentId) {
        ApiResponse created =
                api.createSession("{\"agent\": \"" + agentId + "\", \"environment\": \"" + environmentId + "\"}", null);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        return created.text("id");
    }

    private static void converse(ApiTestClient api, String sessionId, String text) {
        long idleBefore = eventTypes(api, sessionId).stream()
                .filter("session.status_idle"::equals)
                .count();
        assertThat(api.sendMessage(sessionId, text).status()).isEqualTo(202);
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(eventTypes(api, sessionId).stream()
                                .filter("session.status_idle"::equals)
                                .count())
                        .isGreaterThan(idleBefore));
    }

    private static void awaitIdleWith(ApiTestClient api, String sessionId, String text) {
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(
                                api.listEvents(sessionId, "limit=100").body().toString())
                        .contains(text));
        await().atMost(WAIT)
                .untilAsserted(
                        () -> assertThat(eventTypes(api, sessionId).getLast()).isEqualTo("session.status_idle"));
    }

    private static boolean lastBodyContaining(String text) {
        List<ScriptedOpenAiServer.Call> calls = MODEL.calls();
        return calls.stream()
                .filter(call -> call.body() != null)
                .reduce((a, b) -> b)
                .map(call -> call.body().contains(text))
                .orElse(false);
    }

    private static List<String> eventTypes(ApiTestClient api, String sessionId) {
        return api.listEvents(sessionId, "limit=100")
                .body()
                .get("data")
                .valueStream()
                .map(event -> event.get("type").asString())
                .toList();
    }

    private static JsonNode event(ApiTestClient api, String sessionId, String type) {
        return api.listEvents(sessionId, "limit=100")
                .body()
                .get("data")
                .valueStream()
                .filter(event -> event.get("type").asString().equals(type))
                .reduce((a, b) -> b)
                .map(event -> event.get("data"))
                .orElseThrow(() -> new AssertionError("没有事件 " + type));
    }

    private static JsonNode toolResult(ApiTestClient api, String sessionId) {
        return event(api, sessionId, "agent.tool_result");
    }

    private static Path directory() {
        try {
            Path directory = Files.createTempDirectory("agent-config-session-it");
            Files.createDirectories(directory.resolve("budgets"));
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 一个测试租户。
     *
     * @param id 租户 ID
     * @param project 项目 ID
     * @param model 目录中的模型 ID
     * @param api 带本项目 API Key 的客户端
     */
    private record Tenant(String id, String project, String model, ApiTestClient api) {}
}
