package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.SseTestClient;
import cn.refinex.meridian.runtime.support.SseTestClient.Connection;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialProvider;
import cn.refinex.meridian.security.servicejwt.ServiceJwtVerifier;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.security.servicejwt.ServicePrincipal;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import cn.refinex.meridian.testsupport.auth.TestTokenIssuer;
import cn.refinex.meridian.testsupport.model.ScriptedOpenAiServer;
import com.sun.net.httpserver.HttpExchange;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 知识检索的 runtime 一侧（T19-A02 的 {@code knowledge_search} 与前置检索两种形态）：生产装配的会话经 {@code HttpKnowledgeSearch} 调
 * knowledge 的内部接口（替身校验 runtime 的服务 JWT、按请求的标签过滤两条切片），访问标签只取会话的 {@code end_user_attributes}，
 * 模型给不出别的标签；没有对应标签的会话的事件与模型请求里都不出现受限切片。供应商是 {@link ScriptedOpenAiServer}。
 */
@SpringBootTest(
        classes = {RuntimeApplication.class, KnowledgeSessionIT.Credentials.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class KnowledgeSessionIT {

    private static final String TOOL_AGENT = "agt_e2e_kb_tool";
    private static final String PREFETCH_AGENT = "agt_e2e_kb_prefetch";
    private static final String ENVIRONMENT = "env_e2e_kb";
    private static final String KNOWLEDGE_BASE = "kb_e2e_policies";
    private static final Duration WAIT = Duration.ofSeconds(40);

    /** 不限标签的切片。 */
    static final String PUBLIC_PASSAGE = "年休假：工作满 1 年不满 10 年的，年休假 5 天。";

    /** 只有 {@code level:confidential} 能看到的切片。 */
    static final String RESTRICTED_PASSAGE = "年终奖基数为 2 个月基本工资。";

    private static final ScriptedOpenAiServer SERVER =
            ScriptedOpenAiServer.builder().script("""
                    name: kb-search
                    description: 调用 knowledge_search 后回答
                    steps:
                      - tool_calls:
                          - name: knowledge_search
                            arguments: {query: 年终奖发几个月, knowledge_base_ids: [kb_e2e_policies], top_k: 5}
                      - text: 已根据检索结果回答。
                    """).start();

    /** knowledge 的替身：记下收到的检索请求。 */
    static final List<JsonNode> SEARCHES = new CopyOnWriteArrayList<>();

    private static final TestTokenIssuer KNOWLEDGE =
            TestTokenIssuer.start().route("/internal/v1/knowledge-bases:search", KnowledgeSessionIT::search);

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
        registry.add("meridian.runtime.knowledge.url", KNOWLEDGE::url);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TurnWorkerLifecycle lifecycle;

    private static TurnWorkerLifecycle worker;

    @BeforeEach
    void reset() {
        worker = lifecycle;
        SEARCHES.clear();
        SERVER.clearCalls();
    }

    @AfterAll
    static void stopWorkerAndServers() {
        if (worker != null) {
            worker.stop();
        }
        GracefulShutdownManager.getInstance().resetForTesting();
        SERVER.close();
        KNOWLEDGE.close();
    }

    @Test
    void knowledgeSearchCarriesTheSessionTagsAndRestrictedPassagesNeverReachTheEvents() {
        String untagged = runToCompletion(TOOL_AGENT, "[]", "#script:kb-search");
        String tagged = runToCompletion(TOOL_AGENT, "[\"level:confidential\"]", "#script:kb-search");

        assertThat(SEARCHES).hasSize(2);
        JsonNode first = SEARCHES.get(0);
        assertThat(first.get("acl_tags")).isEmpty();
        assertThat(first.get("knowledge_base_ids").get(0).asString()).isEqualTo(KNOWLEDGE_BASE);
        assertThat(first.get("query").asString()).isEqualTo("年终奖发几个月");
        assertThat(first.get("top_k").asInt()).isEqualTo(5);
        assertThat(first.get("rewrite_model").asString()).isEqualTo("mdl_e2e_rewrite");
        assertThat(first.get("tenant_id").asString()).isEqualTo(ApiTestKeys.TENANT);
        assertThat(first.get("session_id").asString()).startsWith("ses_");
        assertThat(first.get("turn_id").asString()).startsWith("trn_");
        assertThat(first.get("api_key_id").asString()).isEqualTo("key_test_a");
        assertThat(SEARCHES.get(1).get("acl_tags").get(0).asString()).isEqualTo("level:confidential");

        assertThat(untagged)
                .as("没有标签的会话：工具结果只有不限标签的切片")
                .contains(PUBLIC_PASSAGE)
                .contains("source=\\\"knowledge_search\\\"")
                .doesNotContain(RESTRICTED_PASSAGE);
        assertThat(tagged).contains(RESTRICTED_PASSAGE);
    }

    @Test
    void prefetchInjectsTaggedPassagesBeforeTheQuestionOnlyForAllowedSessions() {
        String untagged = runToCompletion(PREFETCH_AGENT, "[]", "年终奖发几个月？");

        assertThat(SEARCHES).singleElement().satisfies(request -> {
            assertThat(request.get("query").asString()).isEqualTo("年终奖发几个月？");
            assertThat(request.get("top_k").asInt()).isEqualTo(3);
            assertThat(request.get("acl_tags")).isEmpty();
        });
        String modelRequest = SERVER.calls().getFirst().body();
        assertThat(modelRequest)
                .as("模型请求里有带来源标签的前置检索内容，没有受限切片")
                .contains("<knowledge_context>")
                .contains(PUBLIC_PASSAGE)
                .doesNotContain(RESTRICTED_PASSAGE);
        assertThat(untagged).doesNotContain(RESTRICTED_PASSAGE);

        SEARCHES.clear();
        SERVER.clearCalls();
        runToCompletion(PREFETCH_AGENT, "[\"level:confidential\"]", "年终奖发几个月？");
        assertThat(SEARCHES.getFirst().get("acl_tags").get(0).asString()).isEqualTo("level:confidential");
        assertThat(SERVER.calls().getFirst().body()).contains(RESTRICTED_PASSAGE);
    }

    /** 建会话、发一条消息、等本轮结束，返回全部事件的原文。 */
    private String runToCompletion(String agent, String aclTags, String text) {
        ApiTestClient api = new ApiTestClient(port, ApiTestKeys.KEY_A);
        ApiResponse created = api.createSession(
                "{\"agent\": \"" + agent + "\", \"environment\": \"" + ENVIRONMENT
                        + "\", \"end_user_attributes\": {\"acl_tags\": " + aclTags + "}}",
                null);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        String sessionId = created.text("id");
        Connection stream =
                new SseTestClient(port, ApiTestKeys.KEY_A).open("/v1/sessions/" + sessionId + "/stream", null);
        try {
            assertThat(api.sendMessage(sessionId, text).status()).isEqualTo(202);
            stream.await(
                    frames -> frames.stream().anyMatch(frame -> "session.status_idle".equals(frame.event())), WAIT);
        } finally {
            stream.close();
        }
        ApiResponse events = api.listEvents(sessionId, "limit=100");
        assertThat(events.status()).isEqualTo(200);
        return events.body().toString();
    }

    /** 替身：校验 runtime 的服务 JWT，按请求的标签过滤两条切片。 */
    private static void search(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        Optional<ServicePrincipal> caller = authorization != null && authorization.startsWith("Bearer ")
                ? VERIFIER.resolve(authorization.substring("Bearer ".length()))
                : Optional.empty();
        if (caller.isEmpty() || !caller.get().service().equals(ServiceJwts.RUNTIME)) {
            respond(exchange, 401, "{\"code\":\"auth.invalid_service_credential\"}");
            return;
        }
        JsonNode request =
                JsonMappers.lenient().readTree(exchange.getRequestBody().readAllBytes());
        SEARCHES.add(request);
        List<String> tags = new ArrayList<>();
        request.path("acl_tags").forEach(tag -> tags.add(tag.asString()));
        ObjectNode response = JsonMappers.lenient().createObjectNode();
        response.put("query", request.path("query").asString());
        ArrayNode data = response.putArray("data");
        if (tags.contains("level:confidential")) {
            hit(data, "chk_restricted", "doc_bonus", "compensation.md", RESTRICTED_PASSAGE, 0.95, "第四条 年终奖");
        }
        hit(data, "chk_public", "doc_leave", "attendance.md", PUBLIC_PASSAGE, 0.42, "第二条 年休假");
        respond(exchange, 200, JsonMappers.lenient().writeValueAsString(response));
    }

    private static void hit(
            ArrayNode data,
            String chunk,
            String document,
            String filename,
            String content,
            double score,
            String heading) {
        ObjectNode hit = data.addObject()
                .put("chunk_id", chunk)
                .put("knowledge_base_id", KNOWLEDGE_BASE)
                .put("document_id", document)
                .put("filename", filename)
                .put("kind", "text")
                .put("content", content)
                .put("score", score);
        hit.putNull("page_start");
        hit.putNull("page_end");
        hit.putArray("heading_path").add(heading);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final ServiceJwtVerifier VERIFIER = verifier();

    private static ServiceJwtVerifier verifier() {
        try {
            return ServiceJwtVerifier.remote(
                    URI.create(ApiTestKeys.SERVICE_TOKENS.jwksUrl()).toURL(),
                    ServiceJwts.DEFAULT_ISSUER,
                    ServiceJwts.KNOWLEDGE,
                    Duration.ofMinutes(5));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** runtime 的出站服务 JWT：由测试的令牌签发方签发（真实环境经 control-plane 的令牌端点换取）。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Credentials {

        @Bean
        ServiceCredentialProvider serviceCredentialProvider() {
            return audience -> ApiTestKeys.SERVICE_TOKENS.serviceToken(ServiceJwts.RUNTIME, audience);
        }
    }

    /** 两个智能体（调用 knowledge_search 的与前置检索的）、环境、指向脚本化模型服务的供应商目录。 */
    private static Path writeConfiguration() {
        try {
            Path directory = Files.createTempDirectory("knowledge-session-it");
            Path agents = Files.createDirectories(directory.resolve("agents"));
            Path providers = Files.createDirectories(directory.resolve("providers"));
            Files.createDirectories(directory.resolve("budgets"));
            Files.writeString(providers.resolve("scripted.yaml"), """
                    kind: provider_account
                    provider_account_id: prv_e2e_kb
                    provider: openai_compatible
                    base_url: %s
                    models:
                      - model_id: mdl_e2e_kb
                        upstream_model: scripted
                        context_window: 131072
                        max_output_tokens: 1024
                    """.formatted(SERVER.baseUrl()));
            Files.writeString(agents.resolve("tool-agent.yaml"), agent(TOOL_AGENT, "[knowledge_search]", false));
            Files.writeString(agents.resolve("prefetch-agent.yaml"), agent(PREFETCH_AGENT, "[]", true));
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

    private static String agent(String id, String tools, boolean prefetch) {
        return """
                kind: agent_version
                agent_id: %1$s
                agent_version_id: agv_%5$s
                name: knowledge
                model:
                  model_id: mdl_e2e_kb
                system_prompt: 你是制度问答助手。
                tools: %2$s
                mcp_servers: []
                skills: []
                subagents: []
                permissions:
                  mode: default
                  rules:
                    - {tool_name: knowledge_search, behavior: allow, layer: agent_version}
                compaction:
                  reserved_tokens: 20000
                plan_mode: false
                knowledge:
                  knowledge_base_ids: [%3$s]
                  prefetch: %4$s
                  prefetch_top_k: 3
                  query_rewrite_model: mdl_e2e_rewrite
                max_iters: 10
                expose_thinking: false
                """.formatted(id, tools, KNOWLEDGE_BASE, prefetch, id.substring("agt_".length()));
    }
}
