package cn.refinex.meridian.runtime.retention.app;

/**
 * 租户的事件保留天数（{@code cp.tenants.event_retention_days}，ADR-0049）：生产经 control-plane 内部接口的租户配额读取；没有配置
 * control-plane 时取 {@link RetentionProperties#defaultEventRetentionDays()}。实现可以阻塞，不缓存（每次清理读一次）。
 */
@FunctionalInterface
public interface EventRetentionPolicyPort {

    /**
     * 读取租户的事件保留天数。
     *
     * @param tenantId 租户 ID
     * @return 天数，至少 1
     * @throws RuntimeException 读不到时，调用方跳过该租户，下次再试
     */
    int eventRetentionDays(String tenantId);
}
