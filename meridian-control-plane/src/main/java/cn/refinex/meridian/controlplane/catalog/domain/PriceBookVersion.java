package cn.refinex.meridian.controlplane.catalog.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 价格表的一个版本：发布即生效，不可修改；用量记录按其中的版本计价（ADR-0045 第 8 条）。
 *
 * @param priceBookId 价格表 ID；必填
 * @param version 版本号，从 1 递增
 * @param prices 单价；必填
 * @param effectiveFrom 生效时间（发布时间）；必填
 * @param createdBy 发布者；必填
 */
public record PriceBookVersion(
        String priceBookId, int version, TokenPrices prices, Instant effectiveFrom, String createdBy) {

    /**
     * 创建版本。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public PriceBookVersion {
        Objects.requireNonNull(priceBookId, "priceBookId");
        Objects.requireNonNull(prices, "prices");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(createdBy, "createdBy");
    }
}
