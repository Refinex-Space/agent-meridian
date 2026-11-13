package cn.refinex.meridian.knowledge.support;

import cn.refinex.meridian.common.json.JsonMappers;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 真实模型测试（{@code -Plive}）直接调 DashScope 的 Embedding 与兼容格式的 rerank：runtime 替身把 knowledge 的请求转给它们
 * （{@link StubPlatform#embedder}、{@link StubPlatform#reranker}）。端点与模型见 {@code docs/engineering/testing.md}，模型取
 * {@code MERIDIAN_LIVE_EMBEDDING_MODEL}（默认 {@code qwen3.7-text-embedding}）与 {@code MERIDIAN_LIVE_RERANK_MODEL}（默认
 * {@code qwen3.7-text-rerank}）。密钥只放进请求头，不出现在日志与异常中。
 */
public final class DashScopeModels {

    private static final String EMBEDDINGS = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
    private static final String RERANKS = "https://dashscope.aliyuncs.com/compatible-api/v1/reranks";

    /** 一次 Embedding 请求的条数上限（2026-11-08 实测，{@code dev-config/providers/dashscope.yaml}）。 */
    public static final int MAX_EMBEDDING_BATCH = 20;

    private final String apiKey;
    private final String embeddingModel;
    private final String rerankModel;

    private DashScopeModels(String apiKey) {
        this.apiKey = apiKey;
        this.embeddingModel = System.getenv().getOrDefault("MERIDIAN_LIVE_EMBEDDING_MODEL", "qwen3.7-text-embedding");
        this.rerankModel = System.getenv().getOrDefault("MERIDIAN_LIVE_RERANK_MODEL", "qwen3.7-text-rerank");
    }

    /**
     * 从环境变量 {@code DASHSCOPE_API_KEY} 创建。
     *
     * @return 客户端；没有设置密钥时为空，测试据此跳过
     */
    public static DashScopeModels fromEnvironment() {
        String key = System.getenv("DASHSCOPE_API_KEY");
        return key == null || key.isBlank() ? null : new DashScopeModels(key);
    }

    /**
     * Embedding 模型名。
     *
     * @return 模型名
     */
    public String embeddingModel() {
        return embeddingModel;
    }

    /**
     * rerank 模型名。
     *
     * @return 模型名
     */
    public String rerankModel() {
        return rerankModel;
    }

    /**
     * 1024 维的向量，一次请求，至多 {@value #MAX_EMBEDDING_BATCH} 条。
     *
     * @param texts 文本
     * @return 向量与用量
     */
    public StubPlatform.Embedded embed(List<String> texts) {
        if (texts.size() > MAX_EMBEDDING_BATCH) {
            throw new IllegalArgumentException("一次至多 " + MAX_EMBEDDING_BATCH + " 条：" + texts.size());
        }
        ObjectNode body = JsonMappers.lenient().createObjectNode();
        body.put("model", embeddingModel).put("dimensions", 1024).put("encoding_format", "float");
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        JsonNode json = post(EMBEDDINGS, body);
        List<float[]> vectors = new ArrayList<>(Collections.nCopies(texts.size(), null));
        for (JsonNode item : json.get("data")) {
            JsonNode values = item.get("embedding");
            float[] vector = new float[values.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = values.get(i).floatValue();
            }
            vectors.set(item.get("index").asInt(), vector);
        }
        return new StubPlatform.Embedded(
                vectors, json.path("usage").path("prompt_tokens").asLong());
    }

    /**
     * 给候选打分，一次请求。
     *
     * @param query 查询
     * @param documents 候选
     * @return 与候选同序的得分
     */
    public List<Double> rerank(String query, List<String> documents) {
        ObjectNode body = JsonMappers.lenient().createObjectNode();
        body.put("model", rerankModel).put("query", query);
        ArrayNode input = body.putArray("documents");
        documents.forEach(input::add);
        JsonNode json = post(RERANKS, body);
        List<Double> scores = new ArrayList<>(Collections.nCopies(documents.size(), Double.NEGATIVE_INFINITY));
        for (JsonNode result : json.get("results")) {
            scores.set(
                    result.get("index").asInt(), result.get("relevance_score").asDouble());
        }
        return scores;
    }

    private JsonNode post(String endpoint, ObjectNode body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonMappers.lenient().writeValueAsString(body)))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("DashScope 返回 " + response.statusCode() + "：" + response.body());
            }
            return JsonMappers.lenient().readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("调用 DashScope 失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
