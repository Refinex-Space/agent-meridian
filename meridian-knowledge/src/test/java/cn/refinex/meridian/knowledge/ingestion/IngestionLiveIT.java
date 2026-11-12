package cn.refinex.meridian.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.text.TextTokens;
import cn.refinex.meridian.knowledge.support.KnowledgeTestBase;
import cn.refinex.meridian.knowledge.support.StubPlatform;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 用真实的 Embedding 模型写入一份文档（T18-A04 的 {@code -Plive} 部分）：runtime 替身把 knowledge 发来的批次转给 DashScope 的
 * {@code /compatible-mode/v1/embeddings}，所以 knowledge 一侧的流水线（分批、维度、入库）面对的是真实的向量与用量。同时核对切分用的
 * token 估算（{@link TextTokens#approximate}）与模型报告的用量的偏差。模型取 {@code MERIDIAN_LIVE_EMBEDDING_MODEL}，默认
 * {@code qwen3.7-text-embedding}；约 2 次调用。
 */
@Tag("live")
class IngestionLiveIT extends KnowledgeTestBase {

    private static final String ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";

    @AfterEach
    void restore() {
        PLATFORM.embedder(null);
    }

    @Test
    void aDocumentIsIngestedWithTheRealEmbeddingModel() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "没有设置 DASHSCOPE_API_KEY");
        String model = System.getenv().getOrDefault("MERIDIAN_LIVE_EMBEDDING_MODEL", "qwen3.7-text-embedding");
        AtomicInteger calls = new AtomicInteger();
        AtomicLong reported = new AtomicLong();
        AtomicLong estimated = new AtomicLong();
        PLATFORM.embedder(texts -> {
            calls.incrementAndGet();
            StubPlatform.Embedded embedded = dashscope(apiKey, model, texts);
            reported.addAndGet(embedded.promptTokens());
            estimated.addAndGet(
                    texts.stream().mapToLong(TextTokens::approximate).sum());
            return embedded;
        });
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);

        JsonNode document = awaitFinished(tenant, base, upload(tenant, base, "travel-policy.docx", null));

        assertThat(document.get("status").asString()).as(document.toString()).isEqualTo("ready");
        List<Map<String, Object>> chunks = chunks(document.get("id").asString());
        assertThat(chunks)
                .hasSize(document.get("chunk_count").asInt())
                .allSatisfy(chunk -> assertThat(chunk.get("dims")).isEqualTo(1024));
        assertThat(calls.get()).isBetween(1, 2);
        double error = (double) (estimated.get() - reported.get()) / reported.get();
        System.out.printf(
                "Embedding 用量：模型报告 %d token，切分估算 %d token，偏差 %.1f%%%n", reported.get(), estimated.get(), error * 100);
        assertThat(Math.abs(error)).as("中文正文的估算偏差").isLessThan(0.2);
    }

    private static StubPlatform.Embedded dashscope(String apiKey, String model, List<String> texts) {
        ObjectNode body = JsonMappers.lenient().createObjectNode();
        body.put("model", model).put("dimensions", 1024).put("encoding_format", "float");
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonMappers.lenient().writeValueAsString(body)))
                .build();
        try {
            HttpResponse<String> response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("DashScope 返回 " + response.statusCode());
            }
            JsonNode json = JsonMappers.lenient().readTree(response.body());
            List<float[]> vectors = new ArrayList<>();
            for (JsonNode item : json.get("data")) {
                JsonNode values = item.get("embedding");
                float[] vector = new float[values.size()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = values.get(i).floatValue();
                }
                vectors.add(vector);
            }
            return new StubPlatform.Embedded(
                    vectors, json.path("usage").path("prompt_tokens").asLong());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
