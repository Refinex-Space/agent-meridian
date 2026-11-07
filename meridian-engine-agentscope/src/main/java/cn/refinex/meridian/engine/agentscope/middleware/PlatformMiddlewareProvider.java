package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.assembly.MiddlewareProvider;
import cn.refinex.meridian.engine.spi.port.LeaseRegistry;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.List;
import java.util.Objects;

/**
 * 生产环境的平台中间件提供者，按架构设计「平台中间件」表装配。中间件无状态，所有智能体实例共享同一组实例。
 *
 * <p>T04 提供：{@link PlatformContextMiddleware}（300）、{@link PlatformTracingMiddleware}（200）、
 * {@link LeaseGuardMiddleware}（100）、{@link ToolSpanMiddleware}（50）。括号内是 {@code order()}，数值大的在外层；
 * harness 自带的中间件都是默认值 1，排在平台中间件之内。后续任务在这里加入 {@code SteerMiddleware}、
 * {@code RecoveryNoteMiddleware}（T06、T08）、{@code CompactionGuardMiddleware}（T09a）、{@code PolicyMiddleware}（T10a）、
 * {@code KnowledgePrefetchMiddleware}（T19），子智能体不含 Steer 与 KnowledgePrefetch（T13）。
 */
public final class PlatformMiddlewareProvider implements MiddlewareProvider {

    private final List<MiddlewareBase> middlewares;

    /**
     * 创建提供者。
     *
     * @param leases 本节点持有的租约，供租约守卫使用
     */
    public PlatformMiddlewareProvider(LeaseRegistry leases) {
        Objects.requireNonNull(leases, "leases");
        this.middlewares = List.of(
                new PlatformContextMiddleware(),
                new PlatformTracingMiddleware(),
                new LeaseGuardMiddleware(leases),
                new ToolSpanMiddleware());
    }

    @Override
    public List<MiddlewareBase> middlewares(AgentProfile profile) {
        return middlewares;
    }
}
