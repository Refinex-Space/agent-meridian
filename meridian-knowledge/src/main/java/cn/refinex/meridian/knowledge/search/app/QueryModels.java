package cn.refinex.meridian.knowledge.search.app;

import java.util.List;

/**
 * 检索用到的模型调用，经 runtime 的内部接口（架构设计「检索流程」第 1、2、4 步），模型用量只在 runtime 计量。租户与项目由调用方传入，
 * 用量与预算计入它们。
 *
 * <p>失败以 {@code MeridianException} 结束：模型不存在、种类不符为 {@code knowledge_base.model_unavailable}（409），限流、预算、
 * runtime 或供应商暂时不可用为 {@code service.unavailable}（503）。
 */
public interface QueryModels {

    /**
     * 查询文本的向量。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param model Embedding 模型 ID
     * @param text 查询文本
     * @param dimensions 向量维度
     * @return 向量
     */
    float[] embed(String tenantId, String projectId, String model, String text, int dimensions);

    /**
     * 给候选打分。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param model rerank 模型 ID
     * @param query 查询文本
     * @param documents 候选文本，1 至 100 条
     * @return 每条候选的得分，与 {@code documents} 一一对应、同序；runtime 没有返回的候选为 {@link Double#NEGATIVE_INFINITY}
     */
    List<Double> rerank(String tenantId, String projectId, String model, String query, List<String> documents);

    /**
     * 把用户的问题改写为检索查询。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param model 对话模型 ID
     * @param query 原查询
     * @return 模型的输出（未清理）
     */
    String rewrite(String tenantId, String projectId, String model, String query);
}
