package cn.refinex.meridian.controlplane.budget.domain;

import cn.refinex.meridian.common.budget.BudgetLevel;
import java.time.Instant;
import java.util.Objects;

/**
 * 一级预算（架构设计「计量与预算」，ADR-0048）：单位 token，租户、项目、API Key 按自然月计，会话不分周期。
 *
 * @param id 预算 ID（{@code bdg_…}）
 * @param tenantId 租户 ID
 * @param level 级别
 * @param scopeId 范围 ID；为空表示该级所有范围的默认值，租户级总为空
 * @param projectId 范围所在的项目（项目级、API Key 级）；其余为空
 * @param softLimitTokens 软阈值；为空表示不告警
 * @param hardLimitTokens 硬阈值；为空表示不限
 * @param createdBy 创建者
 * @param createdAt 创建时间
 * @param updatedAt 最近一次修改时间
 */
public record Budget(
        String id,
        String tenantId,
        BudgetLevel level,
        String scopeId,
        String projectId,
        Long softLimitTokens,
        Long hardLimitTokens,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 创建预算。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 租户级带了范围、阈值为负或软阈值大于硬阈值时
     */
    public Budget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (level == BudgetLevel.TENANT && scopeId != null) {
            throw new IllegalArgumentException("租户级预算没有范围 ID");
        }
        checkLimits(softLimitTokens, hardLimitTokens);
    }

    /**
     * 校验一对阈值：不为负，软阈值不大于硬阈值。
     *
     * @param soft 软阈值；可为空
     * @param hard 硬阈值；可为空
     * @throws IllegalArgumentException 不满足时
     */
    public static void checkLimits(Long soft, Long hard) {
        if ((soft != null && soft < 0) || (hard != null && hard < 0)) {
            throw new IllegalArgumentException("阈值不能为负");
        }
        if (soft != null && hard != null && soft > hard) {
            throw new IllegalArgumentException("软阈值不能大于硬阈值");
        }
    }

    /**
     * 是否为某级所有范围的默认值。
     *
     * @return 非租户级且没有范围 ID 时为 true
     */
    public boolean isLevelDefault() {
        return level != BudgetLevel.TENANT && scopeId == null;
    }

    /**
     * 换上新的阈值。
     *
     * @param soft 软阈值
     * @param hard 硬阈值
     * @param now 修改时间
     * @return 新的预算
     */
    public Budget withLimits(Long soft, Long hard, Instant now) {
        return new Budget(id, tenantId, level, scopeId, projectId, soft, hard, createdBy, createdAt, now);
    }
}
