package cn.refinex.meridian.controlplane.catalog.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 模型目录中的一个模型（架构设计「数据架构」中的 {@code models}）。
 *
 * @param id 模型 ID（{@code mdl_…}）；必填
 * @param tenantId 所属租户；必填
 * @param providerAccountId 所属供应商账号；必填，创建后不能改
 * @param name 名称；必填
 * @param kind 种类；必填，创建后不能改
 * @param upstreamModel 供应商侧的模型名；必填
 * @param contextWindow 上下文窗口，大于 0
 * @param maxOutputTokens 默认最大输出，大于 0
 * @param capabilities 能力；必填
 * @param fallbackModelId 备用模型；可为空
 * @param priceBookId 价格表；可为空
 * @param baseUrl 覆盖账号的端点；可为空
 * @param maxBatchSize Embedding 一次请求的输入条数上限；可为空
 * @param rateLimit 覆盖账号的限流；必填（字段可为空）
 * @param createdBy 创建者；必填
 * @param createdAt 创建时间；必填
 * @param updatedAt 修改时间；必填
 */
public record CatalogModel(
        String id,
        String tenantId,
        String providerAccountId,
        String name,
        ModelKind kind,
        String upstreamModel,
        int contextWindow,
        int maxOutputTokens,
        ModelCapabilities capabilities,
        String fallbackModelId,
        String priceBookId,
        String baseUrl,
        Integer maxBatchSize,
        RateLimit rateLimit,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 创建模型。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 窗口、最大输出或批量上限不大于 0、备用模型是自己时
     */
    public CatalogModel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(providerAccountId, "providerAccountId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(upstreamModel, "upstreamModel");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(rateLimit, "rateLimit");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (contextWindow < 1 || maxOutputTokens < 1 || (maxBatchSize != null && maxBatchSize < 1)) {
            throw new IllegalArgumentException("context_window、max_output_tokens、max_batch_size 必须大于 0");
        }
        if (id.equals(fallbackModelId)) {
            throw new IllegalArgumentException("备用模型不能是自己");
        }
    }
}
