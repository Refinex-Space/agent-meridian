package cn.refinex.meridian.knowledge.ingestion.infra;

import cn.refinex.meridian.knowledge.ingestion.app.Embeddings;
import cn.refinex.meridian.knowledge.ingestion.domain.IngestionFailure;
import cn.refinex.meridian.modelclient.EmbeddingClient;
import cn.refinex.meridian.modelclient.EmbeddingRequest;
import cn.refinex.meridian.modelclient.ModelClientException;
import java.util.List;

/**
 * {@link Embeddings} 的实现：{@code meridian-model-client} 调 runtime 的 {@code /internal/v1/embeddings}，带 knowledge 的服务 JWT（ADR-0044）。
 * runtime 的拒绝按它的可重试性转成 {@link IngestionFailure}，错误码沿用 runtime 的（例如 {@code model.not_found}）。
 */
public class RuntimeEmbeddings implements Embeddings {

    private final EmbeddingClient client;

    /**
     * 创建实现。
     *
     * @param client 客户端；为空表示没有配置 runtime 地址
     */
    public RuntimeEmbeddings(EmbeddingClient client) {
        this.client = client;
    }

    @Override
    public List<float[]> embed(String tenantId, String projectId, String model, List<String> texts, int dimensions) {
        if (client == null) {
            throw IngestionFailure.permanent(
                    IngestionFailure.UNAVAILABLE, "没有配置 runtime 地址（meridian.knowledge.runtime.url）");
        }
        try {
            return client.embed(new EmbeddingRequest(tenantId, projectId, model, texts, dimensions))
                    .vectors();
        } catch (ModelClientException e) {
            throw new IngestionFailure(e.code(), e.getMessage(), e.retryable(), e);
        }
    }
}
