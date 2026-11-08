package cn.refinex.meridian.runtime.modelrouting.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelRoute;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Embedding 与 rerank 的供应商 HTTP 客户端：平台自己的 OpenAI 兼容实现（不用 AgentScope 的 RAG 组件），一个 JDK {@link HttpClient}
 * 复用连接。Embedding 走 {@code {base_url}/embeddings}（OpenAI 格式），rerank 走 {@code {base_url}/reranks}（DashScope 的兼容格式，
 * 测试规范「真实模型测试」）。
 *
 * <p>只做一次 HTTP 调用，不重试：重试、限流、预扣与计量在 {@code modelrouting.app} 的用例中。失败以 {@link ProviderCallException}
 * 结束，带状态码与 {@code Retry-After}。不记录请求与响应正文（日志规范：不记录输入文本）。
 *
 * <p>线程安全：可被并发调用。
 */
public final class ProviderVectorClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProviderVectorClient.class);

    /** 建立连接的超时。 */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 一次请求的超时：Embedding 与 rerank 是非流式的短请求。 */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final JsonMapper JSON = JsonMappers.lenient();

    private final HttpClient client;
    private final Clock clock;

    /**
     * 创建客户端。
     *
     * @param clock 时钟，解析 HTTP 日期格式的 {@code Retry-After}
     */
    public ProviderVectorClient(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * 一次 Embedding 请求。
     *
     * @param route 路由（端点、凭据、供应商侧模型名）
     * @param inputs 文本，不超过模型的单次上限
     * @param dimensions 输出维度；可为空
     * @return 向量与用量；失败以 {@link ProviderCallException} 结束
     */
    public Mono<EmbeddingResult> embed(ModelRoute route, List<String> inputs, Integer dimensions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", route.upstreamModel());
        body.put("input", inputs);
        body.put("encoding_format", "float");
        if (dimensions != null) {
            body.put("dimensions", dimensions);
        }
        return post(route, "/embeddings", body).map(json -> {
            List<float[]> vectors = new ArrayList<>(inputs.size());
            for (int i = 0; i < inputs.size(); i++) {
                vectors.add(null);
            }
            for (JsonNode item : json.path("data")) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= inputs.size()) {
                    throw malformed("Embedding 结果的 index 越界：" + index);
                }
                JsonNode values = item.path("embedding");
                float[] vector = new float[values.size()];
                for (int j = 0; j < vector.length; j++) {
                    vector[j] = (float) values.get(j).asDouble();
                }
                vectors.set(index, vector);
            }
            if (vectors.contains(null)) {
                throw malformed("Embedding 结果缺少部分输入的向量");
            }
            return new EmbeddingResult(vectors, promptTokens(json));
        });
    }

    /**
     * 一次 rerank 请求。
     *
     * @param route 路由
     * @param query 查询
     * @param documents 候选文本
     * @param topN 只返回前 n 条；可为空
     * @return 按得分从高到低的结果与用量；失败以 {@link ProviderCallException} 结束
     */
    public Mono<RerankResult> rerank(ModelRoute route, String query, List<String> documents, Integer topN) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", route.upstreamModel());
        body.put("query", query);
        body.put("documents", documents);
        if (topN != null) {
            body.put("top_n", topN);
        }
        return post(route, "/reranks", body).map(json -> {
            List<RerankScore> scores = new ArrayList<>();
            for (JsonNode item : json.path("results")) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= documents.size()) {
                    throw malformed("rerank 结果的 index 越界：" + index);
                }
                scores.add(new RerankScore(index, item.path("relevance_score").asDouble()));
            }
            scores.sort((a, b) -> Double.compare(b.score(), a.score()));
            return new RerankResult(scores, promptTokens(json));
        });
    }

    @Override
    public void close() {
        client.close();
    }

    private Mono<JsonNode> post(ModelRoute route, String path, Map<String, Object> body) {
        return Mono.defer(() -> {
                    HttpRequest.Builder request = HttpRequest.newBuilder(endpoint(route, path))
                            .timeout(REQUEST_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    JSON.writeValueAsString(body), StandardCharsets.UTF_8));
                    if (!"https".equalsIgnoreCase(request.build().uri().getScheme())) {
                        // 明文连接不做 h2c 升级（与 ProviderHttpTransport 相同）
                        request.version(HttpClient.Version.HTTP_1_1);
                    }
                    if (route.apiKey() != null) {
                        request.header(
                                "Authorization", "Bearer " + route.apiKey().reveal());
                    }
                    return Mono.fromFuture(
                            () -> client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()), false);
                })
                .onErrorMap(ProviderVectorClient::connectionFailure)
                .map(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        log.warn("供应商返回错误状态 model={} status={}", route.modelId(), response.statusCode());
                        throw new ProviderCallException(
                                "供应商返回状态 " + response.statusCode(),
                                response.statusCode(),
                                retryAfter(response.headers()
                                        .firstValue("Retry-After")
                                        .orElse(null)),
                                null);
                    }
                    try {
                        return JSON.readTree(response.body());
                    } catch (RuntimeException e) {
                        throw malformed("供应商响应不是 JSON");
                    }
                });
    }

    private static URI endpoint(ModelRoute route, String path) {
        if (route.baseUrl() == null) {
            throw new IllegalStateException("模型 " + route.modelId() + " 没有配置端点");
        }
        String base = route.baseUrl().endsWith("/")
                ? route.baseUrl().substring(0, route.baseUrl().length() - 1)
                : route.baseUrl();
        return URI.create(base + path);
    }

    private static Integer promptTokens(JsonNode json) {
        JsonNode usage = json.path("usage");
        JsonNode tokens = usage.has("prompt_tokens") ? usage.path("prompt_tokens") : usage.path("total_tokens");
        return tokens.isNumber() ? tokens.asInt() : null;
    }

    private static Throwable connectionFailure(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof IOException || cause instanceof TimeoutException) {
            return new ProviderCallException("供应商连接失败：" + cause.getMessage(), null, Optional.empty(), cause);
        }
        return cause;
    }

    private static ProviderCallException malformed(String message) {
        // 响应格式不对重试也不会好：按 4xx 一类的拒绝处理
        return new ProviderCallException(message, 0, Optional.empty(), null);
    }

    /** 解析 {@code Retry-After}：秒数或 HTTP 日期。 */
    private Optional<Duration> retryAfter(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim()))));
        } catch (NumberFormatException e) {
            try {
                Instant at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                Duration wait = Duration.between(clock.instant(), at);
                return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        }
    }

    /**
     * Embedding 结果。
     *
     * @param vectors 向量，与输入一一对应
     * @param promptTokens 供应商报告的输入 token；没有报告时为空
     */
    public record EmbeddingResult(List<float[]> vectors, Integer promptTokens) {}

    /**
     * rerank 结果。
     *
     * @param scores 得分，从高到低
     * @param promptTokens 供应商报告的输入 token；没有报告时为空
     */
    public record RerankResult(List<RerankScore> scores, Integer promptTokens) {}

    /**
     * 一条候选文本的得分。
     *
     * @param index 在请求中的序号
     * @param score 相关度
     */
    public record RerankScore(int index, double score) {}

    /**
     * 一次供应商调用的失败。
     *
     * <p>{@code status} 为空表示连接失败或超时，为 0 表示响应格式不对。
     */
    public static final class ProviderCallException extends RuntimeException {

        private final Integer status;
        private final Duration retryAfter;

        ProviderCallException(String message, Integer status, Optional<Duration> retryAfter, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.retryAfter = retryAfter.orElse(null);
        }

        /**
         * HTTP 状态码。
         *
         * @return 状态码；连接失败时为空，响应格式不对时为 0
         */
        public Integer status() {
            return status;
        }

        /**
         * 供应商要求的等待。
         *
         * @return 等待时长；没有时为空
         */
        public Optional<Duration> retryAfter() {
            return Optional.ofNullable(retryAfter);
        }

        /**
         * 是否可重试：连接失败、超时、429 与 5xx（架构设计「模型调用」中的错误分类）。
         *
         * @return 可重试时为真
         */
        public boolean retryable() {
            return status == null || status == 429 || status >= 500;
        }
    }
}
