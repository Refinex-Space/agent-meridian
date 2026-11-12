package cn.refinex.meridian.runtime.modelrouting.domain;

import java.util.Objects;

/**
 * 模型目录中的一个模型（架构设计「数据架构」中 control-plane 的 {@code models}，ADR-0045）。
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
 * @param tenantId 所属租户：只有该租户的调用可以使用（ADR-0045）；开发期的文件目录为空，表示不限租户
 * @param fallbackModelId 目录中的备用模型；可为空
 * @param priceBookId 价格表；可为空，表示没有定价
 * @param priceBookVersion 价格表当前生效的版本，写进用量记录；没有价格表时为空
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
        int maxBatchSize,
        String tenantId,
        String fallbackModelId,
        String priceBookId,
        Integer priceBookVersion) {

    /** 没有配置时一次 Embedding 请求的输入条数上限：DashScope 的 {@code qwen3.7-text-embedding} 实测上限 20，取一半留余量。 */
    public static final int DEFAULT_MAX_BATCH_SIZE = 10;

    /**
     * 是否可以被某个租户使用（D23：模型属于租户，租户内的全部项目都可以使用）。
     *
     * @param tenant 调用方租户
     * @return 可以使用时为 true
     */
    public boolean usableBy(String tenant) {
        return tenantId == null || tenantId.equals(tenant);
    }

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
