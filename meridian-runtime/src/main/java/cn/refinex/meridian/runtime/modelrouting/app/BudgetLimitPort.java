package cn.refinex.meridian.runtime.modelrouting.app;

import cn.refinex.meridian.runtime.modelrouting.domain.BudgetScope;
import java.util.List;

/**
 * 四级预算硬阈值的内部端口（架构设计「计量与预算」，ADR-0048 第 2、10 条）。生产实现 {@code infra.RedisBudgetLimits} 读 control-plane
 * 同步到 Redis 的限额：具体范围优先、其次是该级的默认值，都没有时为 0（新租户默认零预算）。测试实现 {@code infra.FileBudgetLimits} 读
 * 开发配置目录，没有配置的级别不限。
 *
 * <p>方法会阻塞，响应式调用方经 {@code Blocking} 调用。
 */
public interface BudgetLimitPort {

    /**
     * 给各级范围填上硬阈值。
     *
     * @param tenantId 租户 ID
     * @param scopes 各级范围（硬阈值待填）
     * @return 填好硬阈值的范围，顺序不变；负数表示不限
     */
    List<BudgetScope> withLimits(String tenantId, List<BudgetScope> scopes);
}
