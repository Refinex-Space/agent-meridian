package cn.refinex.meridian.modelclient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.web.client.RestClient;

/**
 * 调用 runtime 内部接口 {@code POST /internal/v1/rerank} 的客户端（架构设计「检索流程」第 4 步）：按与查询的相关度给候选文本打分。
 * 限流、预算与计量在 runtime。传入的 {@link RestClient} 与 {@link EmbeddingClient} 相同，带 knowledge 的服务 JWT。
 *
 * <p>线程安全：无状态，可共享。
 */
public final class RerankClient {

    /** 一次请求的最大候选数，与内部接口的 {@code maxItems} 相同。 */
    public static final int MAX_DOCUMENTS = 100;

    static final String PATH = "/internal/v1/rerank";

    private final RestClient runtime;

    /**
     * 创建客户端。
     *
     * @param runtime 指向 runtime、带服务 JWT 的客户端
     */
    public RerankClient(RestClient runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * 给候选文本打分。
     *
     * @param request 请求
     * @return 得分，按相关度从高到低
     * @throws ModelClientException runtime 拒绝请求或不可用，或返回的序号不合法时
     */
    public RerankResult rerank(RerankRequest request) {
        WireResponse response = RuntimeCalls.post(runtime, PATH, WireRequest.of(request), WireResponse.class);
        if (response.results() == null) {
            throw RuntimeCalls.incomplete("runtime 返回的重排结果为空");
        }
        List<RerankResult.Score> scores = new ArrayList<>(response.results().size());
        for (WireResult result : response.results()) {
            if (result.index() < 0 || result.index() >= request.documents().size()) {
                throw RuntimeCalls.incomplete("runtime 返回的重排序号不合法：" + result.index());
            }
            scores.add(new RerankResult.Score(result.index(), result.relevanceScore()));
        }
        long tokens = response.usage() == null ? 0 : response.usage().promptTokens();
        return new RerankResult(scores, tokens);
    }

    /** 内部接口的请求体（{@code InternalRerankRequest}）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WireRequest(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("model") String model,
            @JsonProperty("query") String query,
            @JsonProperty("documents") List<String> documents,
            @JsonProperty("top_n") Integer topN) {

        static WireRequest of(RerankRequest request) {
            return new WireRequest(
                    request.tenantId(),
                    request.projectId(),
                    request.model(),
                    request.query(),
                    request.documents(),
                    request.topN());
        }
    }

    /** 内部接口的响应体（{@code InternalRerankResponse}）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireResponse(
            @JsonProperty("results") List<WireResult> results,
            @JsonProperty("usage") EmbeddingClient.WireUsage usage) {}

    /** 一条得分。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireResult(
            @JsonProperty("index") int index,
            @JsonProperty("relevance_score") double relevanceScore) {}
}
