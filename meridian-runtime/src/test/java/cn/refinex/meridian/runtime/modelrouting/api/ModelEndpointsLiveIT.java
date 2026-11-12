package cn.refinex.meridian.runtime.modelrouting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.runtime.modelrouting.infra.RedisBudgetLedger;
import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.ContractValidator;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;

/**
 * 真实模型测试（T09b-A05）：经 HTTP 调用 runtime 的模型接口，供应商是 DashScope。模型取 {@code MERIDIAN_LIVE_CHAT_MODEL}（默认
 * {@code qwen-plus}）、{@code MERIDIAN_LIVE_EMBEDDING_MODEL}（默认 {@code qwen3.7-text-embedding}）、{@code MERIDIAN_LIVE_RERANK_MODEL}
 * （默认 {@code qwen3.7-text-rerank}），凭据取环境变量 {@code DASHSCOPE_API_KEY}，没有设置时跳过。只断言结构性条件（测试规范
 * 「真实模型测试」）。
 *
 * <p>成本：3 次调用（一次最大输出 32 token 的流式对话、一次两条文本的 Embedding、一次两条候选的 rerank）。
 */
@Tag("live")
@DirtiesContext
class ModelEndpointsLiveIT extends ApiTestBase {

    private static final String CHAT_MODEL =
            Objects.requireNonNullElse(System.getenv("MERIDIAN_LIVE_CHAT_MODEL"), "qwen-plus");
    private static final String EMBEDDING_MODEL =
            Objects.requireNonNullElse(System.getenv("MERIDIAN_LIVE_EMBEDDING_MODEL"), "qwen3.7-text-embedding");
    private static final String RERANK_MODEL =
            Objects.requireNonNullElse(System.getenv("MERIDIAN_LIVE_RERANK_MODEL"), "qwen3.7-text-rerank");

    private static final Path DIRECTORY = writeProviders();

    @BeforeAll
    static void requireApiKey() {
        String key = System.getenv("DASHSCOPE_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "没有设置 DASHSCOPE_API_KEY，跳过真实模型测试");
    }

    @DynamicPropertySource
    static void liveProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "meridian.runtime.model-routing.providers-directory",
                () -> DIRECTORY.resolve("providers").toString());
        registry.add(
                "meridian.runtime.model-routing.budgets-directory",
                () -> DIRECTORY.resolve("budgets").toString());
    }

    @Test
    void streamingChatCompletionIsSettledWithTheReportedUsage() {
        long apiKeyUsedBefore = new RedisBudgetLedger(RuntimeTestRedis.template())
                .used(ApiTestKeys.TENANT, BudgetLevel.API_KEY, "key_test_a");

        EntityExchangeResult<byte[]> result = client().post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ApiTestKeys.KEY_A)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model": "mdl_live_chat", "stream": true, "stream_options": {"include_usage": true},
                         "max_tokens": 32, "messages": [{"role": "user", "content": "用一句话介绍你自己。"}]}
                        """)
                .exchange()
                .expectBody(byte[].class)
                .returnResult();

        assertThat(result.getStatus().value()).isEqualTo(200);
        List<String> data = new ArrayList<>();
        for (String line : new String(result.getResponseBody(), StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("data:")) {
                data.add(line.substring(5).strip());
            }
        }
        assertThat(data.getLast()).isEqualTo(ModelsApiController.DONE);
        StringBuilder text = new StringBuilder();
        JsonNode usage = null;
        for (String chunk : data.subList(0, data.size() - 1)) {
            ContractValidator.validateSchema(chunk, "ChatCompletionChunk");
            JsonNode node = JsonMappers.lenient().readTree(chunk);
            for (JsonNode choice : node.get("choices")) {
                if (choice.get("delta").has("content")) {
                    text.append(choice.get("delta").get("content").asString());
                }
            }
            if (node.has("usage")) {
                usage = node.get("usage");
            }
        }
        assertThat(text.toString()).as("流式输出了文本").isNotBlank();
        assertThat(usage).as("include_usage 时有用量块").isNotNull();
        int prompt = usage.get("prompt_tokens").asInt();
        int completion = usage.get("completion_tokens").asInt();
        assertThat(prompt).isPositive();
        assertThat(completion).isPositive();

        Map<String, Object> record = usage(ApiTestKeys.TENANT).getLast();
        assertThat(record).containsEntry("usage_source", "provider").containsEntry("model_id", "mdl_live_chat");
        assertThat(record).containsEntry("input_tokens", prompt).containsEntry("output_tokens", completion);
        assertThat(new RedisBudgetLedger(RuntimeTestRedis.template())
                                .used(ApiTestKeys.TENANT, BudgetLevel.API_KEY, "key_test_a")
                        - apiKeyUsedBefore)
                .as("预算按供应商报告的输入加输出结算")
                .isEqualTo(prompt + completion);
    }

    @Test
    void embeddingReturns1024Dimensions() {
        String tenant = "ten_live_" + System.nanoTime();
        JsonNode response = internal(
                "/internal/v1/embeddings",
                "{\"tenant_id\": \"" + tenant + "\", \"model\": \"mdl_live_embedding\","
                        + " \"input\": [\"差旅住宿标准\", \"报销需要哪些票据\"]}");

        assertThat(response.get("data")).hasSize(2);
        assertThat(response.get("data").get(0).get("embedding")).hasSize(1024);
        assertThat(response.get("data").get(1).get("embedding")).hasSize(1024);
        assertThat(usage(tenant))
                .singleElement()
                .satisfies(record -> assertThat(record)
                        .containsEntry("kind", "embedding_tokens")
                        .containsEntry("usage_source", "provider")
                        .containsEntry(
                                "input_tokens",
                                response.get("usage").get("prompt_tokens").asInt()));
    }

    @Test
    void rerankOrdersTheRelevantDocumentFirst() {
        String tenant = "ten_live_" + System.nanoTime();
        JsonNode response = internal(
                "/internal/v1/rerank",
                "{\"tenant_id\": \"" + tenant + "\", \"model\": \"mdl_live_rerank\", \"query\": \"苹果是什么颜色\","
                        + " \"documents\": [\"香蕉是黄色的\", \"苹果通常是红色的\"]}");

        JsonNode results = response.get("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("index").asInt()).as("与查询相关的候选排第一").isEqualTo(1);
        assertThat(results.get(0).get("relevance_score").asDouble())
                .isGreaterThan(results.get(1).get("relevance_score").asDouble());
        assertThat(usage(tenant))
                .singleElement()
                .satisfies(record -> assertThat(record).containsEntry("kind", "rerank_tokens"));
    }

    private JsonNode internal(String path, String body) {
        EntityExchangeResult<byte[]> result = client().post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ApiTestKeys.knowledgeToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectBody(byte[].class)
                .returnResult();
        assertThat(result.getStatus().value()).isEqualTo(200);
        return JsonMappers.lenient().readTree(new String(result.getResponseBody(), StandardCharsets.UTF_8));
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(120))
                .build();
    }

    private static List<Map<String, Object>> usage(String tenant) {
        return DbAssertions.list(
                        "SELECT payload::text FROM rt.outbox WHERE topic = 'usage' AND payload->>'tenant_id' = ?"
                                + " ORDER BY created_at, id",
                        String.class,
                        tenant)
                .stream()
                .map(json -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = JsonMappers.lenient().readValue(json, Map.class);
                    return payload;
                })
                .toList();
    }

    /** 指向 DashScope 的供应商目录：凭据只写环境变量名，rerank 走 {@code compatible-api/v1}。 */
    private static Path writeProviders() {
        try {
            Path directory = Files.createTempDirectory("model-endpoints-live-it");
            Files.createDirectories(directory.resolve("providers"));
            Files.createDirectories(directory.resolve("budgets"));
            Files.writeString(directory.resolve("providers").resolve("dashscope.yaml"), """
                    kind: provider_account
                    provider_account_id: prv_live_dashscope
                    provider: openai_compatible
                    base_url: https://dashscope.aliyuncs.com/compatible-mode/v1
                    secret_ref: DASHSCOPE_API_KEY
                    models:
                      - model_id: mdl_live_chat
                        upstream_model: %s
                        context_window: 131072
                        max_output_tokens: 64
                      - model_id: mdl_live_embedding
                        kind: embedding
                        upstream_model: %s
                        context_window: 8192
                        max_output_tokens: 1
                        max_batch_size: 20
                      - model_id: mdl_live_rerank
                        kind: rerank
                        upstream_model: %s
                        base_url: https://dashscope.aliyuncs.com/compatible-api/v1
                        context_window: 8192
                        max_output_tokens: 1
                    """.formatted(
                            CHAT_MODEL, EMBEDDING_MODEL, RERANK_MODEL));
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
