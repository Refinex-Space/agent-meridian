package cn.refinex.meridian.knowledge.ingestion.app;

import cn.refinex.meridian.knowledge.ingestion.domain.IngestionFailure;
import java.util.List;

/** 计算向量：经 runtime 的内部接口（架构设计「写入流程」第 4 步），模型调用只在 runtime 计量。 */
public interface Embeddings {

    /**
     * 计算一批文本的向量。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param model Embedding 模型 ID
     * @param texts 文本，1 至 64 条
     * @param dimensions 期望的维度
     * @return 与输入同序的向量
     * @throws IngestionFailure runtime 拒绝（不可重试）、暂时不可用或限流（可重试）时
     */
    List<float[]> embed(String tenantId, String projectId, String model, List<String> texts, int dimensions);
}
