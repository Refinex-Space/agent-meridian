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
 * 生产环境的平台中间件提供者，按架构设计「平台中间件」表装配。除压缩守护的外层外，中间件无状态，所有智能体实例共享同一组实例。
 *
 * <p>T04 提供：{@link PlatformContextMiddleware}（300）、{@link PlatformTracingMiddleware}（200）、
 * {@link LeaseGuardMiddleware}（100）、{@link ToolSpanMiddleware}（50）。T06 提供：{@link RecoveryNoteMiddleware}（90）、
 * {@link BatchCheckpointMiddleware}（80，可关闭）。T08 提供：{@link InterruptCheckpointMiddleware}（88）、{@link SteerMiddleware}
 * （85，只挂在主智能体上，必须在批次 checkpoint 之外）。T09a 提供：{@link CompactionGuardMiddleware}（外层 20，按智能体配置的
 * 压缩预留创建）与 {@link CompactionGuardMiddleware.Inner}（0），夹住 harness 的 {@code CompactionMiddleware}（1）；
 * {@link ContextCompositionMiddleware}（10，只用 {@code onModelCall}）。括号内是 {@code order()}，数值大的在外层；harness 自带的
 * 中间件都是默认值 1。后续任务在这里加入 {@code PolicyMiddleware}（T10a）、{@code KnowledgePrefetchMiddleware}（T19），
 * 子智能体不含 Steer 与 KnowledgePrefetch（T13）。
 */
public final class PlatformMiddlewareProvider implements MiddlewareProvider {

    private final LeaseRegistry leases;
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
     * @param leases 本节点持有的租约，供租约守卫与压缩守护使用
     * @param inbox 收件箱，供 steer 中间件取出 steer 消息
     * @param batchCheckpoint 是否在每批工具执行完成后保存状态（P3）；关闭时强杀只能整轮恢复
     */
    public PlatformMiddlewareProvider(LeaseRegistry leases, InboxPort inbox, boolean batchCheckpoint) {
        this.leases = Objects.requireNonNull(leases, "leases");
        Objects.requireNonNull(inbox, "inbox");
        List<MiddlewareBase> shared = new ArrayList<>(List.of(
                new PlatformContextMiddleware(),
                new PlatformTracingMiddleware(),
                new LeaseGuardMiddleware(leases),
                new RecoveryNoteMiddleware(),
                new InterruptCheckpointMiddleware(),
                new ToolSpanMiddleware(),
                new PolicyMiddleware(),
                new ContextCompositionMiddleware(),
                new CompactionGuardMiddleware.Inner()));
        if (batchCheckpoint) {
            shared.add(new BatchCheckpointMiddleware());
        }
        this.subagentMiddlewares = List.copyOf(shared);
        shared.add(new SteerMiddleware(inbox));
        this.mainMiddlewares = List.copyOf(shared);
    }

    @Override
    public List<MiddlewareBase> middlewares(AgentProfile profile) {
        List<MiddlewareBase> base =
                profile == null || profile.subagent() == null ? mainMiddlewares : subagentMiddlewares;
        if (profile == null) {
            return base;
        }
        // 压缩守护的外层要知道该智能体的压缩预留，才能用与 CompactionMiddleware 相同的阈值判断本次是否会压缩
        List<MiddlewareBase> withGuard = new ArrayList<>(base);
        withGuard.add(new CompactionGuardMiddleware(
                leases, profile.agent().compaction().reservedTokens()));
        return List.copyOf(withGuard);
    }
}
