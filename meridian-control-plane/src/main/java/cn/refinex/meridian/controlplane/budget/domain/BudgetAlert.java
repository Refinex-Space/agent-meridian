package cn.refinex.meridian.controlplane.budget.domain;

import cn.refinex.meridian.common.budget.BudgetLevel;
import java.time.Instant;

/**
 * 一条预算告警：账本汇总越过软阈值（ADR-0048 第 4 条）。
 *
 * @param id 告警 ID（{@code bal_…}）
 * @param tenantId 租户 ID
 * @param level 级别
 * @param scopeId 范围 ID
 * @param period 周期
 * @param kind 种类，目前只有 {@value #SOFT_LIMIT_REACHED}
 * @param thresholdTokens 越过的阈值
 * @param usedTokens 越过时的账本汇总
 * @param occurredAt 越过阈值的那条用量的发生时间
 */
public record BudgetAlert(
        String id,
        String tenantId,
        BudgetLevel level,
        String scopeId,
        String period,
        String kind,
        long thresholdTokens,
        long usedTokens,
        Instant occurredAt) {

    /** 越过软阈值的告警种类，也是审计动作 {@code budget.soft_limit_reached} 的后半部分。 */
    public static final String SOFT_LIMIT_REACHED = "soft_limit_reached";
}
