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
 */
public record CatalogModel(
        String modelId,
        String providerAccountId,
        String upstreamModel,
        int contextWindow,
        int maxOutputTokens,
        RateLimit rateLimit) {

    /**
     * 创建目录模型。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 窗口或最大输出不大于 0 时
     */
    public CatalogModel {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(providerAccountId, "providerAccountId");
        Objects.requireNonNull(upstreamModel, "upstreamModel");
        if (contextWindow < 1 || maxOutputTokens < 1) {
            throw new IllegalArgumentException("context_window 与 max_output_tokens 必须大于 0：" + modelId);
        }
    }
}
