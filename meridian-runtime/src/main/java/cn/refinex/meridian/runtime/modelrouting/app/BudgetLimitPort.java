package cn.refinex.meridian.runtime.modelrouting.app;

import cn.refinex.meridian.common.budget.BudgetLevel;

/**
 * 四级预算硬阈值的内部端口（架构设计「计量与预算」）。T09a 的开发实现读 {@code dev-config/budgets}
 * （{@code infra.FileBudgetLimits}），没有配置的级别不限；T16 起改为 control-plane 同步到 Redis 的限额，新租户默认零预算。
 *
 * <p>方法会阻塞，响应式调用方经 {@code Blocking} 调用。
 */
public interface BudgetLimitPort {

    /**
     * 一级预算的硬阈值。
     *
     * @param level 级别
     * @param scopeId 范围 ID
     * @return 硬阈值（token）；负数表示不限
     */
    long hardLimit(BudgetLevel level, String scopeId);
}
