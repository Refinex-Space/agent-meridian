package cn.refinex.meridian.runtime.dispatch.app;

/**
 * 租户并发配额的来源（架构设计「认领」中的租户并发计数行，ADR-0042）：生产中经 control-plane 内部接口读取
 * {@code cp.tenants.max_concurrent_turns}；没有配置 control-plane（测试）时取 {@code default-tenant-max-running}。
 *
 * <p>实现可以阻塞；只在为租户创建计数行、收到 {@code config.invalidate} 时调用，不缓存。
 */
@FunctionalInterface
public interface TenantQuotaPort {

    /**
     * 读取租户的并发配额。
     *
     * @param tenantId 租户 ID
     * @return 并发配额，至少 1
     */
    int maxConcurrentTurns(String tenantId);
}
