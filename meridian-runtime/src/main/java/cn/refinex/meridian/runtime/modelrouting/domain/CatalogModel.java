package cn.refinex.meridian.runtime.modelrouting.domain;

import java.util.Objects;

/**
 * 模型目录中的一个模型（架构设计「数据架构」中 control-plane 的 {@code models}）。
 *
 * @param modelId 模型 ID（{@code mdl_…}）；必填
 * @param providerAccountId 所属供应商账号；必填
 * @param upstreamModel 供应商侧的模型名；必填
 * @param contextWindow 上下文窗口，大于 0
 * @param maxOutputTokens 请求没有指定时的最大输出，大于 0
 * @param rateLimit 本模型的限流参数；可为空，表示取账号的
 * @param kind 模型种类；必填
 * @param baseUrl 本模型的端点，覆盖账号的端点；可为空。DashScope 的 rerank 不在 {@code compatible-mode/v1} 下，而在
 *     {@code compatible-api/v1}（测试规范「真实模型测试」）
 * @param maxBatchSize 一次供应商请求最多携带的输入条数，大于 0；Embedding 按它拆批，其余种类不使用
 */
public record CatalogModel(
        String modelId,
        String providerAccountId,
        String upstreamModel,
        int contextWindow,
        int maxOutputTokens,
        RateLimit rateLimit,
        ModelKind kind,
        String baseUrl,
        int maxBatchSize) {

    /** 没有配置时一次 Embedding 请求的输入条数上限：DashScope 的 {@code qwen3.7-text-embedding} 实测上限 20，取一半留余量。 */
    public static final int DEFAULT_MAX_BATCH_SIZE = 10;

    /**
     * 创建目录模型。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 窗口、最大输出或批量上限不大于 0 时
     */
    public CatalogModel {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(providerAccountId, "providerAccountId");
        Objects.requireNonNull(upstreamModel, "upstreamModel");
        Objects.requireNonNull(kind, "kind");
        if (contextWindow < 1 || maxOutputTokens < 1) {
            throw new IllegalArgumentException("context_window 与 max_output_tokens 必须大于 0：" + modelId);
        }
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("max_batch_size 必须大于 0：" + modelId);
        }
    }
}
