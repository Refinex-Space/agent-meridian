package cn.refinex.meridian.runtime.modelrouting.api;

import cn.refinex.meridian.api.internal.model.InternalEmbedding;
import cn.refinex.meridian.api.internal.model.InternalEmbeddingRequest;
import cn.refinex.meridian.api.internal.model.InternalEmbeddingResponse;
import cn.refinex.meridian.api.internal.model.InternalRerankRequest;
import cn.refinex.meridian.api.internal.model.InternalRerankResponse;
import cn.refinex.meridian.api.internal.model.InternalRerankResult;
import cn.refinex.meridian.api.internal.model.InternalUsage;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.runtime.modelrouting.app.VectorModelService;
import cn.refinex.meridian.runtime.openapi.internal.InternalModelsApi;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialAuthorization;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 内部接口（规范 {@code meridian-internal-v1.yaml}）：知识库写入与检索用的 {@code /internal/v1/embeddings} 与 {@code /internal/v1/rerank}
 * （架构设计「知识库架构」）。
 *
 * <p>服务凭据由 {@code ServiceCredentialWebFilter} 在进入处理器之前校验。内部调用代表的租户与项目由请求体声明，这里据此建立
 * {@code PlatformContext}，用量与预算计入它们（没有 API Key 与会话两级）。
 */
@RestController
public class InternalModelsApiController implements InternalModelsApi {

    private final VectorModelService vectors;

    /**
     * 创建处理器。
     *
     * @param vectors 向量与重排用例
     */
    public InternalModelsApiController(VectorModelService vectors) {
        this.vectors = vectors;
    }

    @Override
    public Mono<ResponseEntity<InternalEmbeddingResponse>> createInternalEmbeddings(
            Mono<InternalEmbeddingRequest> internalEmbeddingRequest, ServerWebExchange exchange) {
        ServiceCredentialAuthorization.require(exchange);
        return internalEmbeddingRequest.flatMap(request -> vectors.embed(
                        request.getModel(), request.getInput(), request.getDimensions())
                .map(result -> {
                    List<InternalEmbedding> data =
                            new ArrayList<>(result.vectors().size());
                    for (int i = 0; i < result.vectors().size(); i++) {
                        data.add(
                                new InternalEmbedding(i, floats(result.vectors().get(i))));
                    }
                    return ResponseEntity.ok(new InternalEmbeddingResponse(
                            data, request.getModel(), new InternalUsage(result.inputTokens(), result.inputTokens())));
                })
                .contextWrite(PlatformContexts.write(context(request.getTenantId(), request.getProjectId()))));
    }

    @Override
    public Mono<ResponseEntity<InternalRerankResponse>> createInternalRerank(
            Mono<InternalRerankRequest> internalRerankRequest, ServerWebExchange exchange) {
        ServiceCredentialAuthorization.require(exchange);
        return internalRerankRequest.flatMap(request -> vectors.rerank(
                        request.getModel(), request.getQuery(), request.getDocuments(), request.getTopN())
                .map(result -> ResponseEntity.ok(new InternalRerankResponse(
                        result.scores().stream()
                                .map(score -> new InternalRerankResult(score.index(), score.score()))
                                .toList(),
                        request.getModel(),
                        new InternalUsage(result.inputTokens(), result.inputTokens()))))
                .contextWrite(PlatformContexts.write(context(request.getTenantId(), request.getProjectId()))));
    }

    private static PlatformContext context(String tenantId, String projectId) {
        return PlatformContext.builder().tenantId(tenantId).projectId(projectId).build();
    }

    private static List<Float> floats(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }
}
