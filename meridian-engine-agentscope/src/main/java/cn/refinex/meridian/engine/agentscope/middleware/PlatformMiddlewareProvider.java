package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.assembly.MiddlewareProvider;
import cn.refinex.meridian.engine.spi.port.InboxPort;
import cn.refinex.meridian.engine.spi.port.LeaseRegistry;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 生产环境的平台中间件提供者，按架构设计「平台中间件」表装配。中间件无状态，所有智能体实例共享同一组实例。
 *
 * <p>T04 提供：{@link PlatformContextMiddleware}（300）、{@link PlatformTracingMiddleware}（200）、
 * {@link LeaseGuardMiddleware}（100）、{@link ToolSpanMiddleware}（50）。T06 提供：{@link RecoveryNoteMiddleware}（90）、
 * {@link BatchCheckpointMiddleware}（80，可关闭）。T08 提供：{@link InterruptCheckpointMiddleware}（88）、{@link SteerMiddleware}
 * （85，只挂在主智能体上，必须在批次 checkpoint 之外）。括号内是 {@code order()}，数值大的在外层；harness 自带的中间件都是默认值
 * 1，排在平台中间件之内。后续任务在这里加入 {@code CompactionGuardMiddleware}（T09a）、{@code PolicyMiddleware}（T10a）、
 * {@code KnowledgePrefetchMiddleware}（T19），子智能体不含 Steer 与 KnowledgePrefetch（T13）。
 */
public final class PlatformMiddlewareProvider implements MiddlewareProvider {

    private final List<MiddlewareBase> subagentMiddlewares;
    private final List<MiddlewareBase> mainMiddlewares;

    /**
     * 创建提供者，开启批次级 checkpoint（默认行为，ADR-0028）。
     *
     * @param leases 本节点持有的租约，供租约守卫使用
     * @param inbox 收件箱，供 steer 中间件取出 steer 消息
     */
    public PlatformMiddlewareProvider(LeaseRegistry leases, InboxPort inbox) {
        this(leases, inbox, true);
    }

    /**
     * 创建提供者。
     *
     * @param leases 本节点持有的租约，供租约守卫使用
     * @param inbox 收件箱，供 steer 中间件取出 steer 消息
     * @param batchCheckpoint 是否在每批工具执行完成后保存状态（P3）；关闭时强杀只能整轮恢复
     */
    public PlatformMiddlewareProvider(LeaseRegistry leases, InboxPort inbox, boolean batchCheckpoint) {
        Objects.requireNonNull(leases, "leases");
        Objects.requireNonNull(inbox, "inbox");
        List<MiddlewareBase> shared = new ArrayList<>(List.of(
                new PlatformContextMiddleware(),
                new PlatformTracingMiddleware(),
                new LeaseGuardMiddleware(leases),
                new RecoveryNoteMiddleware(),
                new InterruptCheckpointMiddleware(),
                new ToolSpanMiddleware()));
        if (batchCheckpoint) {
            shared.add(new BatchCheckpointMiddleware());
        }
        this.subagentMiddlewares = List.copyOf(shared);
        shared.add(new SteerMiddleware(inbox));
        this.mainMiddlewares = List.copyOf(shared);
    }

    @Override
    public List<MiddlewareBase> middlewares(AgentProfile profile) {
        return profile == null || profile.subagent() == null ? mainMiddlewares : subagentMiddlewares;
    }
}
