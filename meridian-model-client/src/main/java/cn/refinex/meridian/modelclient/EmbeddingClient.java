package cn.refinex.meridian.modelclient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * 调用 runtime 内部接口 {@code POST /internal/v1/embeddings} 的客户端（架构设计「写入流程」第 4 步）：knowledge 的写入与检索都经它
 * 计算向量，模型调用的限流、预算与计量只在 runtime 一处发生，knowledge 自己不产生用量记录。
 *
 * <p>传入的 {@link RestClient} 由 {@code ServiceRestClients.create(runtimeUrl, ServiceJwts.RUNTIME)} 构造，每个请求带 knowledge
 * 换取的服务 JWT（ADR-0044）。一次至多 {@value #MAX_BATCH} 条，调用方负责分批。
 *
 * <p>线程安全：无状态，可共享。
 */
public final class EmbeddingClient {

    /** 一次请求的最大条数：架构设计「写入流程」规定每批 64 条，与内部接口的 {@code maxItems} 相同。 */
    public static final int MAX_BATCH = 64;

    static final String PATH = "/internal/v1/embeddings";

    private final RestClient runtime;

    /**
     * 创建客户端。
     *
     * @param runtime 指向 runtime、带服务 JWT 的客户端
     */
    public EmbeddingClient(RestClient runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * 计算一批文本的向量。
     *
     * @param request 请求
     * @return 与输入一一对应的向量与用量
     * @throws ModelClientException runtime 拒绝请求或不可用时；{@link ModelClientException#retryable()} 区分能否重试
     */
    public EmbeddingResult embed(EmbeddingRequest request) {
        WireResponse response;
        try {
            response = runtime.post()
                    .uri(PATH)
                    .body(WireRequest.of(request))
                    .retrieve()
                    .body(WireResponse.class);
        } catch (RestClientResponseException e) {
            throw ModelClientException.fromResponse(e.getStatusCode(), problemCode(e), problemDetail(e));
        } catch (RestClientException e) {
            throw new ModelClientException(
                    true, 0, ModelClientException.UNAVAILABLE, "runtime 不可用：" + e.getMessage(), e);
        }
        if (response == null
                || response.data() == null
                || response.data().size() != request.inputs().size()) {
            throw new ModelClientException(true, 0, ModelClientException.UNAVAILABLE, "runtime 返回的向量条数与输入不一致", null);
        }
        float[][] vectors = new float[request.inputs().size()][];
        for (WireEmbedding item : response.data()) {
            if (item.index() < 0 || item.index() >= vectors.length || item.embedding() == null) {
                throw new ModelClientException(
                        true, 0, ModelClientException.UNAVAILABLE, "runtime 返回的向量序号不合法：" + item.index(), null);
            }
            vectors[item.index()] = item.embedding();
        }
        List<float[]> ordered = new ArrayList<>(vectors.length);
        for (float[] vector : vectors) {
            if (vector == null) {
                throw new ModelClientException(true, 0, ModelClientException.UNAVAILABLE, "runtime 返回的向量有缺失", null);
            }
            ordered.add(vector);
        }
        long tokens = response.usage() == null ? 0 : response.usage().promptTokens();
        return new EmbeddingResult(ordered, tokens);
    }

    private static String problemCode(RestClientResponseException e) {
        JsonNode problem = problem(e);
        return problem == null ? null : problem.path("code").asString(null);
    }

    private static String problemDetail(RestClientResponseException e) {
        JsonNode problem = problem(e);
        String detail = problem == null ? null : problem.path("detail").asString(null);
        return detail != null ? detail : e.getStatusText();
    }

    private static JsonNode problem(RestClientResponseException e) {
        try {
            return e.getResponseBodyAs(JsonNode.class);
        } catch (RuntimeException ignored) {
            // 响应体不是 problem+json（例如网关返回的 HTML）：只按状态码判断
            return null;
        }
    }

    /** 内部接口的请求体（{@code InternalEmbeddingRequest}）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WireRequest(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("model") String model,
            @JsonProperty("input") List<String> input,
            @JsonProperty("dimensions") Integer dimensions) {

        static WireRequest of(EmbeddingRequest request) {
            return new WireRequest(
                    request.tenantId(), request.projectId(), request.model(), request.inputs(), request.dimensions());
        }
    }

    /** 内部接口的响应体（{@code InternalEmbeddingResponse}）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireResponse(
            @JsonProperty("data") List<WireEmbedding> data,
            @JsonProperty("usage") WireUsage usage) {}

    /** 一条向量。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireEmbedding(
            @JsonProperty("index") int index,
            @JsonProperty("embedding") float[] embedding) {}

    /** 用量。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireUsage(@JsonProperty("prompt_tokens") long promptTokens) {}

    static boolean retryable(HttpStatusCode status) {
        return status.value() == 429 || status.is5xxServerError();
    }
}
