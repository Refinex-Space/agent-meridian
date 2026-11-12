package cn.refinex.meridian.runtime.modelrouting.api;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.runtime.modelrouting.infra.RedisBudgetLedger;
import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.ContractValidator;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import cn.refinex.meridian.testsupport.model.ScriptedOpenAiServer;
import cn.refinex.meridian.testsupport.model.ScriptedVectors;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;

/**
 * 模型直通与内部接口经 HTTP 的集成测试（T09b-A01、A02）：完整的 runtime，供应商是 {@link ScriptedOpenAiServer}。每次交互按
 * {@code meridian-v1.yaml}（内部接口按 {@code meridian-internal-v1.yaml}）校验，流式的每个块按 {@code ChatCompletionChunk} 校验；
 * 限流、预扣与计量由预算计数、限额与 outbox 用量记录证明。
 */
@DirtiesContext
class ModelEndpointsIT extends ApiTestBase {

    private static final String CHAT = "mdl_it_scripted";
    private static final String LIMITED_CHAT = "mdl_it_limited";
    private static final String EMBEDDING = "mdl_it_embedding";
    private static final String RERANK = "mdl_it_rerank";

    /** 预算只有 10 token 的 Key，证明直通调用经过四级预扣。 */
    private static final String KEY_TINY_BUDGET = "mk_test_models_tiny_budget";

    private static final String KEY_TINY_BUDGET_ID = "key_test_tiny_budget";

    private static final ScriptedOpenAiServer SERVER =
            ScriptedOpenAiServer.builder().start();

    private static final Path DIRECTORY = writeDirectories();

    @DynamicPropertySource
    static void modelProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "meridian.runtime.model-routing.providers-directory",
                () -> DIRECTORY.resolve("providers").toString());
        registry.add(
                "meridian.runtime.model-routing.budgets-directory",
                () -> DIRECTORY.resolve("budgets").toString());
        String prefix = "meridian.security.dev-api-keys.keys[3].";
        registry.add(prefix + "key", () -> KEY_TINY_BUDGET);
        registry.add(prefix + "api-key-id", () -> KEY_TINY_BUDGET_ID);
        registry.add(prefix + "tenant-id", () -> ApiTestKeys.TENANT);
        registry.add(prefix + "project-id", () -> ApiTestKeys.PROJECT_A);
        registry.add(prefix + "scopes[0]", () -> "models:invoke");
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @BeforeEach
    void clearCalls() {
        SERVER.clearCalls();
    }

    // ---- /v1/chat/completions（A01） ----

    @Test
    void nonStreamingCompletionFollowsTheOpenAiFormatAndIsMetered() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        long apiKeyUsedBefore = used(BudgetLevel.API_KEY, "key_test_a");
        int usageBefore = usageRecords().size();

        ApiResponse response = api.call(
                "POST /v1/chat/completions", HttpMethod.POST, "/v1/chat/completions", """
                {"model": "%s", "max_tokens": 128, "temperature": 0.2,
                 "messages": [{"role": "system", "content": "你是客服"}, {"role": "user", "content": "#script:tool-once"}],
                 "tools": [{"type": "function", "function": {"name": "lookup_order", "description": "查询订单",
                   "parameters": {"type": "object", "properties": {"order_id": {"type": "string"}}}}}]}
                """.formatted(CHAT), null);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.body();
        assertThat(body.get("object").asString()).isEqualTo("chat.completion");
        assertThat(body.get("id").asString()).startsWith("chatcmpl-");
        assertThat(body.get("model").asString()).isEqualTo(CHAT);
        JsonNode choice = body.get("choices").get(0);
        assertThat(choice.get("finish_reason").asString()).isEqualTo("tool_calls");
        JsonNode message = choice.get("message");
        assertThat(message.get("content").asString()).isEqualTo("我先查一下订单。");
        JsonNode call = message.get("tool_calls").get(0);
        assertThat(call.get("type").asString()).isEqualTo("function");
        assertThat(call.get("function").get("name").asString()).isEqualTo("lookup_order");
        assertThat(JsonMappers.lenient()
                        .readTree(call.get("function").get("arguments").asString())
                        .get("order_id")
                        .asString())
                .isEqualTo("o-1001");
        JsonNode usage = body.get("usage");
        int total = usage.get("total_tokens").asInt();
        assertThat(total)
                .isEqualTo(usage.get("prompt_tokens").asInt()
                        + usage.get("completion_tokens").asInt());

        // 计量：一条用量记录，带 API Key、不带会话；预算计数按"输入加输出"结算
        List<Map<String, Object>> records = usageRecords();
        assertThat(records).hasSize(usageBefore + 1);
        Map<String, Object> record = records.getLast();
        assertThat(record).containsEntry("model_id", CHAT).containsEntry("purpose", "reasoning");
        assertThat(record).containsEntry("api_key_id", "key_test_a").containsEntry("usage_source", "provider");
        assertThat(record.get("session_id")).isNull();
        assertThat(record.get("input_tokens"))
                .isEqualTo(usage.get("prompt_tokens").asInt());
        assertThat(used(BudgetLevel.API_KEY, "key_test_a") - apiKeyUsedBefore).isEqualTo(total);
        assertThat(SERVER.calls())
                .singleElement()
                .satisfies(c -> assertThat(c.stream()).as("平台对供应商一律流式").isTrue());
    }

    @Test
    void streamingCompletionSendsOpenAiChunksUsageAndDone() {
        Stream stream = stream(ApiTestKeys.KEY_A, """
                {"model": "%s", "stream": true, "stream_options": {"include_usage": true},
                 "messages": [{"role": "user", "content": "#script:chat"}]}
                """.formatted(CHAT));

        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.data().getLast()).isEqualTo(ModelsApiController.DONE);
        List<JsonNode> chunks = stream.chunks();
        String id = chunks.getFirst().get("id").asString();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.get("object").asString()).isEqualTo("chat.completion.chunk");
            assertThat(chunk.get("id").asString()).isEqualTo(id);
        });
        assertThat(chunks.getFirst()
                        .get("choices")
                        .get(0)
                        .get("delta")
                        .get("role")
                        .asString())
                .isEqualTo("assistant");
        StringBuilder text = new StringBuilder();
        String finishReason = null;
        for (JsonNode chunk : chunks) {
            for (JsonNode choice : chunk.get("choices")) {
                if (choice.get("delta").has("content")) {
                    text.append(choice.get("delta").get("content").asString());
                }
                if (choice.hasNonNull("finish_reason")) {
                    finishReason = choice.get("finish_reason").asString();
                }
            }
        }
        assertThat(text.toString()).isEqualTo("你好，我是脚本化模型。这是一段固定的回答，用来验证普通对话的完整事件序列。");
        assertThat(finishReason).isEqualTo("stop");
        assertThat(chunks.size()).as("逐块输出").isGreaterThan(3);
        JsonNode usage = chunks.getLast();
        assertThat(usage.get("choices")).isEmpty();
        assertThat(usage.get("usage").get("total_tokens").asInt()).isPositive();
        assertThat(usageRecords().getLast()).containsEntry("model_id", CHAT);
    }

    @Test
    void streamingWithoutIncludeUsageHasNoUsageChunk() {
        Stream stream = stream(ApiTestKeys.KEY_A, """
                {"model": "%s", "stream": true, "messages": [{"role": "user", "content": "#script:tool-once"}]}
                """.formatted(CHAT));

        assertThat(stream.chunks()).noneMatch(chunk -> chunk.has("usage"));
        List<JsonNode> calls = new ArrayList<>();
        stream.chunks()
                .forEach(chunk -> chunk.get("choices").forEach(choice -> {
                    if (choice.get("delta").has("tool_calls")) {
                        choice.get("delta").get("tool_calls").forEach(calls::add);
                    }
                }));
        assertThat(calls.getFirst().get("id").asString()).startsWith("call_");
        assertThat(calls.getFirst().get("function").get("name").asString()).isEqualTo("lookup_order");
        StringBuilder arguments = new StringBuilder();
        calls.forEach(call -> {
            assertThat(call.get("index").asInt()).isZero();
            if (call.has("function") && call.get("function").has("arguments")) {
                arguments.append(call.get("function").get("arguments").asString());
            }
        });
        assertThat(arguments.toString()).isEqualTo("{\"order_id\":\"o-1001\"}");
    }

    @Test
    void errorsBeforeTheFirstChunkAreProblemJsonWithTheirStatus() {
        Stream unavailable = stream(ApiTestKeys.KEY_A, """
                {"model": "%s", "stream": true, "messages": [{"role": "user", "content": "#script:error-before-first-chunk"}]}
                """.formatted(CHAT));

        assertThat(unavailable.status()).isEqualTo(503);
        assertThat(unavailable.problemCode()).isEqualTo("model.provider_unavailable");
        assertThat(SERVER.calls()).as("首包之前重试 2 次").hasSize(3);
    }

    @Test
    void aBrokenStreamEndsWithAnErrorChunk() {
        Stream broken = stream(ApiTestKeys.KEY_A, """
                {"model": "%s", "stream": true, "messages": [{"role": "user", "content": "#script:error-after-first-chunk"}]}
                """.formatted(CHAT));

        assertThat(broken.status()).isEqualTo(200);
        String last = broken.data().getLast();
        assertThat(last).isNotEqualTo(ModelsApiController.DONE);
        assertThat(JsonMappers.lenient().readTree(last).get("error").get("code").asString())
                .isEqualTo("model.provider_unavailable");
    }

    @Test
    void budgetAndRateLimitApplyToDirectCalls() {
        ApiResponse overBudget = api(KEY_TINY_BUDGET)
                .call(
                        "POST /v1/chat/completions",
                        HttpMethod.POST,
                        "/v1/chat/completions",
                        "{\"model\": \"" + CHAT + "\", \"messages\": [{\"role\": \"user\", \"content\": \"你好\"}]}",
                        null);
        assertThat(overBudget.status()).isEqualTo(429);
        assertThat(overBudget.text("code")).isEqualTo("model.budget_exceeded");
        assertThat(SERVER.calls()).as("预扣失败不调用供应商").isEmpty();

        String request = "{\"model\": \"" + LIMITED_CHAT + "\", \"max_tokens\": 16,"
                + " \"messages\": [{\"role\": \"user\", \"content\": \"你好\"}]}";
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        assertThat(api.call("POST /v1/chat/completions", HttpMethod.POST, "/v1/chat/completions", request, null)
                        .status())
                .isEqualTo(200);
        ApiResponse limited =
                api.call("POST /v1/chat/completions", HttpMethod.POST, "/v1/chat/completions", request, null);
        assertThat(limited.status()).as("rpm=1：第二次要等 60 秒，超过 30 秒的上限").isEqualTo(429);
        assertThat(limited.text("code")).isEqualTo("model.rate_limit_timeout");
    }

    @Test
    void idempotentNonStreamingCompletionsAreReplayed() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        String request = "{\"model\": \"" + CHAT + "\", \"messages\": [{\"role\": \"user\", \"content\": \"幂等\"}]}";
        String key = "chat-" + System.nanoTime();

        ApiResponse first =
                api.call("POST /v1/chat/completions", HttpMethod.POST, "/v1/chat/completions", request, key);
        ApiResponse second =
                api.call("POST /v1/chat/completions", HttpMethod.POST, "/v1/chat/completions", request, key);

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()
                        .get("choices")
                        .get(0)
                        .get("message")
                        .get("content")
                        .asString())
                .isEqualTo("echo: 幂等");
        assertThat(second.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.text("id")).isEqualTo(first.text("id"));
        assertThat(SERVER.calls()).hasSize(1);

        ApiResponse streaming = api.call(
                "POST /v1/chat/completions",
                HttpMethod.POST,
                "/v1/chat/completions",
                "{\"model\": \"" + CHAT
                        + "\", \"stream\": true, \"messages\": [{\"role\": \"user\", \"content\": \"x\"}]}",
                "stream-" + System.nanoTime());
        assertThat(streaming.status()).isEqualTo(400);
        assertThat(streaming.text("code")).isEqualTo("request.validation_failed");
    }

    @Test
    void scopesModelKindsAndUnknownFieldsAreChecked() {
        String request = "{\"model\": \"" + CHAT + "\", \"messages\": [{\"role\": \"user\", \"content\": \"你好\"}]}";
        ApiResponse forbidden = api(ApiTestKeys.KEY_A_READ_ONLY)
                .call("POST /v1/chat/completions", HttpMethod.POST, "/v1/chat/completions", request, null);
        assertThat(forbidden.status()).isEqualTo(403);

        ApiResponse wrongKind = api(ApiTestKeys.KEY_A)
                .call(
                        "POST /v1/chat/completions",
                        HttpMethod.POST,
                        "/v1/chat/completions",
                        "{\"model\": \"" + EMBEDDING + "\", \"messages\": [{\"role\": \"user\", \"content\": \"你好\"}]}",
                        null);
        assertThat(wrongKind.status()).isEqualTo(400);
        assertThat(wrongKind.text("code")).isEqualTo("model.unsupported_operation");

        ApiResponse unknownField = api(ApiTestKeys.KEY_A)
                .callInvalid(
                        "POST /v1/chat/completions",
                        HttpMethod.POST,
                        "/v1/chat/completions",
                        "{\"model\": \"" + CHAT
                                + "\", \"top_k\": 3, \"messages\": [{\"role\": \"user\", \"content\": \"你好\"}]}",
                        Map.of());
        assertThat(unknownField.status()).isEqualTo(400);

        ApiResponse toolWithoutId = api(ApiTestKeys.KEY_A)
                .call(
                        "POST /v1/chat/completions",
                        HttpMethod.POST,
                        "/v1/chat/completions",
                        "{\"model\": \"" + CHAT + "\", \"messages\": [{\"role\": \"tool\", \"content\": \"结果\"}]}",
                        null);
        assertThat(toolWithoutId.status()).isEqualTo(400);
        assertThat(toolWithoutId.body().get("errors").get(0).get("field").asString())
                .isEqualTo("messages.0.tool_call_id");
    }

    // ---- /v1/embeddings 与内部接口（A02） ----

    @Test
    void publicEmbeddingsReturnVectorsInBothEncodingsAndAreMetered() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);

        ApiResponse floats = api.call(
                "POST /v1/embeddings",
                HttpMethod.POST,
                "/v1/embeddings",
                "{\"model\": \"" + EMBEDDING + "\", \"input\": [\"差旅标准\", \"报销票据\", \"住宿\"], \"dimensions\": 8}",
                null);
        ApiResponse base64 = api.call(
                "POST /v1/embeddings",
                HttpMethod.POST,
                "/v1/embeddings",
                "{\"model\": \"" + EMBEDDING
                        + "\", \"input\": \"差旅标准\", \"dimensions\": 8, \"encoding_format\": \"base64\"}",
                null);

        assertThat(floats.status()).isEqualTo(200);
        assertThat(floats.body().get("object").asString()).isEqualTo("list");
        JsonNode data = floats.body().get("data");
        assertThat(data).hasSize(3);
        float[] expected = ScriptedVectors.embed("差旅标准", 8);
        for (int i = 0; i < expected.length; i++) {
            assertThat(data.get(0).get("embedding").get(i).floatValue()).isEqualTo(expected[i]);
        }
        assertThat(data.get(2).get("index").asInt()).isEqualTo(2);
        assertThat(floats.body().get("usage").get("prompt_tokens").asInt()).isEqualTo(10);
        ByteBuffer decoded = ByteBuffer.wrap(Base64.getDecoder()
                        .decode(base64.body()
                                .get("data")
                                .get(0)
                                .get("embedding")
                                .asString()))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertThat(decoded.getFloat()).isEqualTo(expected[0]);
        assertThat(SERVER.calls()).as("单次上限 2 条：3 条拆成 2 批，加 1 次单条请求").hasSize(3);
        assertThat(usageRecords()
                        .subList(usageRecords().size() - 3, usageRecords().size()))
                .allSatisfy(record -> {
                    assertThat(record).containsEntry("kind", "embedding_tokens");
                    assertThat(record).containsEntry("api_key_id", "key_test_a");
                });
    }

    @Test
    void internalEmbeddingsRequireAServiceCredentialAndChargeTheDeclaredTenant() {
        String tenant = "ten_internal_" + System.nanoTime();
        String body = "{\"tenant_id\": \"" + tenant + "\", \"project_id\": \"prj_internal\", \"model\": \"" + EMBEDDING
                + "\", \"input\": [\"切片一\", \"切片二\"], \"dimensions\": 4}";

        InternalResponse unauthenticated = internal("/internal/v1/embeddings", body, null);
        InternalResponse wrongCredential = internal("/internal/v1/embeddings", body, ApiTestKeys.KEY_A);
        InternalResponse accepted = internal("/internal/v1/embeddings", body, ApiTestKeys.knowledgeToken());

        assertThat(unauthenticated.status()).isEqualTo(401);
        assertThat(unauthenticated.body().get("code").asString()).isEqualTo("auth.unauthenticated");
        assertThat(wrongCredential.status()).isEqualTo(401);
        assertThat(wrongCredential.body().get("code").asString()).isEqualTo("auth.invalid_service_credential");
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.body().get("data")).hasSize(2);
        assertThat(accepted.body().get("data").get(1).get("embedding")).hasSize(4);
        assertThat(SERVER.calls()).as("被拒绝的请求不到达供应商").hasSize(1);
        assertThat(usage(tenant)).singleElement().satisfies(record -> {
            assertThat(record).containsEntry("project_id", "prj_internal");
            assertThat(record).containsEntry("kind", "embedding_tokens");
            assertThat(record.get("api_key_id")).isNull();
        });
        assertThat(used(tenant, BudgetLevel.PROJECT, "prj_internal"))
                .isEqualTo(accepted.body().get("usage").get("prompt_tokens").asLong());
    }

    @Test
    void internalRerankOrdersDocumentsAndIsMetered() {
        String tenant = "ten_internal_" + System.nanoTime();
        InternalResponse response = internal(
                "/internal/v1/rerank",
                "{\"tenant_id\": \"" + tenant + "\", \"model\": \"" + RERANK + "\", \"query\": \"住宿标准\", \"top_n\": 2,"
                        + " \"documents\": [\"报销需要发票\", \"差旅住宿标准为每晚 500 元\", \"住宿需要提前预订\"]}",
                ApiTestKeys.knowledgeToken());

        assertThat(response.status()).isEqualTo(200);
        JsonNode results = response.body().get("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("index").asInt()).isEqualTo(1);
        assertThat(results.get(1).get("index").asInt()).isEqualTo(2);
        assertThat(usage(tenant))
                .singleElement()
                .satisfies(record -> assertThat(record)
                        .containsEntry("kind", "rerank_tokens")
                        .containsEntry("purpose", "rerank"));
        assertThat(internal(
                                "/internal/v1/rerank",
                                "{\"tenant_id\": \"" + tenant + "\", \"model\": \"" + RERANK
                                        + "\", \"query\": \"x\", \"documents\": [\"y\"]}",
                                null)
                        .status())
                .isEqualTo(401);
    }

    // ---- 辅助 ----

    private Stream stream(String apiKey, String body) {
        EntityExchangeResult<byte[]> result = client().post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectBody(byte[].class)
                .returnResult();
        String text =
                result.getResponseBody() == null ? "" : new String(result.getResponseBody(), StandardCharsets.UTF_8);
        SimpleResponse.Builder response =
                SimpleResponse.Builder.status(result.getStatus().value());
        result.getResponseHeaders().forEach(response::withHeader);
        List<String> data = new ArrayList<>();
        if (result.getStatus().value() == 200) {
            assertThat(result.getResponseHeaders().getContentType())
                    .isNotNull()
                    .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                            .isTrue());
            for (String line : text.split("\n")) {
                if (line.startsWith("data:")) {
                    data.add(line.substring(5).strip());
                }
            }
            // 每个块（除了结束标记与错误块）按规范的 ChatCompletionChunk 校验
            for (String chunk : data) {
                if (!chunk.equals(ModelsApiController.DONE) && !chunk.startsWith("{\"error\"")) {
                    ContractValidator.validateSchema(chunk, "ChatCompletionChunk");
                }
            }
        } else {
            response.withBody(text);
            ContractValidator.validate(
                    "POST /v1/chat/completions",
                    new SimpleRequest.Builder(Request.Method.POST, "/v1/chat/completions")
                            .withHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                            .withContentType(MediaType.APPLICATION_JSON_VALUE)
                            .withBody(body)
                            .build(),
                    response.build());
        }
        return new Stream(result.getStatus().value(), data, text);
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(60))
                .build();
    }

    private static long used(BudgetLevel level, String scopeId) {
        return used(ApiTestKeys.TENANT, level, scopeId);
    }

    private static long used(String tenant, BudgetLevel level, String scopeId) {
        return new RedisBudgetLedger(RuntimeTestRedis.template()).used(tenant, level, scopeId);
    }

    private static List<Map<String, Object>> usageRecords() {
        return usage(ApiTestKeys.TENANT);
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

    /** 临时目录中的供应商目录（指向脚本化模型服务）与预算限额。 */
    private static Path writeDirectories() {
        try {
            Path directory = Files.createTempDirectory("model-endpoints-it");
            Files.createDirectories(directory.resolve("providers"));
            Files.createDirectories(directory.resolve("budgets"));
            Files.writeString(directory.resolve("providers").resolve("scripted.yaml"), """
                    kind: provider_account
                    provider_account_id: prv_it_scripted
                    provider: openai_compatible
                    base_url: %s
                    models:
                      - model_id: %s
                        upstream_model: scripted
                        context_window: 32000
                        max_output_tokens: 512
                      - model_id: %s
                        upstream_model: scripted-limited
                        context_window: 32000
                        max_output_tokens: 64
                        rate_limit:
                          rpm: 1
                          tpm: 0
                      - model_id: %s
                        kind: embedding
                        upstream_model: scripted-embedding
                        context_window: 8192
                        max_output_tokens: 1
                        max_batch_size: 2
                      - model_id: %s
                        kind: rerank
                        upstream_model: scripted-rerank
                        base_url: %s
                        context_window: 8192
                        max_output_tokens: 1
                    """.formatted(
                            SERVER.baseUrl(), CHAT, LIMITED_CHAT, EMBEDDING, RERANK, SERVER.rerankBaseUrl()));
            Files.writeString(directory.resolve("budgets").resolve("tiny.yaml"), """
                    kind: budget
                    level: api_key
                    scope_id: "%s"
                    hard_limit_tokens: 10
                    """.formatted(KEY_TINY_BUDGET_ID));
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 一次流式请求的结果。 */
    private record Stream(int status, List<String> data, String raw) {

        List<JsonNode> chunks() {
            return data.stream()
                    .filter(chunk -> !chunk.equals(ModelsApiController.DONE))
                    .map(chunk -> JsonMappers.lenient().readTree(chunk))
                    .toList();
        }

        String problemCode() {
            return JsonMappers.lenient().readTree(raw).get("code").asString();
        }
    }
}
