package cn.refinex.meridian.controlplane.catalog.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 价格表（ADR-0045 第 8 条）：版本只追加，最近发布的版本即当前生效的版本。
 *
 * @param id 价格表 ID（{@code prb_…}）；必填
 * @param tenantId 所属租户；必填
 * @param name 名称；必填
 * @param currency 币种（ISO 4217）；必填
 * @param currentVersion 当前生效的版本号，不小于 1
 * @param createdBy 创建者；必填
 * @param createdAt 创建时间；必填
 * @param updatedAt 最近一次发布的时间；必填
 */
public record PriceBook(
        String id,
        String tenantId,
        String name,
        String currency,
        int currentVersion,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 创建价格表。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public PriceBook {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
