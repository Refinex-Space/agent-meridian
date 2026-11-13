package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.assembly.MiddlewareProvider;
import cn.refinex.meridian.engine.spi.port.InboxPort;
import cn.refinex.meridian.engine.spi.port.KnowledgeSearchPort;
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
 * {@link ContextCompositionMiddleware}（10，只用 {@code onModelCall}）。T10a 提供 {@link PolicyMiddleware}（70，T13 起另限制每轮
 * 拉起子智能体的数量）。T19 提供 {@link KnowledgePrefetchMiddleware}（150，只用 {@code onAgent}，按智能体的知识库配置创建，只给开启了
 * 前置检索的主智能体）。括号内是 {@code order()}，数值大的在外层；harness 自带的中间件都是默认值 1。
 *
 * <p>子智能体挂载另一组中间件（架构设计「子智能体」）：不含 {@link SteerMiddleware} 与 {@link KnowledgePrefetchMiddleware}；
 * 恢复说明、中断检查点、批次 checkpoint 三个中间件也在其中，但引擎不往子线程的 {@code RuntimeContext} 放它们读取的标记，所以
 * 对子线程不起作用（子线程不会被恢复，见 ADR-0040）。
 */
public final class PlatformMiddlewareProvider implements MiddlewareProvider {

    private final LeaseRegistry leases;
    private final KnowledgeSearchPort knowledge;
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
        this(leases, inbox, batchCheckpoint, PolicyMiddleware.DEFAULT_MAX_SUBAGENTS_PER_TURN);
    }

    /**
     * 创建提供者。
     *
     * @param leases 本节点持有的租约，供租约守卫与压缩守护使用
     * @param inbox 收件箱，供 steer 中间件取出 steer 消息
     * @param batchCheckpoint 是否在每批工具执行完成后保存状态（P3）；关闭时强杀只能整轮恢复
     * @param maxSubagentsPerTurn 每轮拉起子智能体的上限（架构设计「子智能体」，默认 8）
     */
    public PlatformMiddlewareProvider(
            LeaseRegistry leases, InboxPort inbox, boolean batchCheckpoint, int maxSubagentsPerTurn) {
        this(leases, inbox, batchCheckpoint, maxSubagentsPerTurn, null);
    }

    /**
     * 创建提供者。
     *
     * @param leases 本节点持有的租约，供租约守卫与压缩守护使用
     * @param inbox 收件箱，供 steer 中间件取出 steer 消息
     * @param batchCheckpoint 是否在每批工具执行完成后保存状态（P3）；关闭时强杀只能整轮恢复
     * @param maxSubagentsPerTurn 每轮拉起子智能体的上限（架构设计「子智能体」，默认 8）
     * @param knowledge 知识库检索，前置检索使用；为空时不挂前置检索（智能体配置了也不生效）
     */
    public PlatformMiddlewareProvider(
            LeaseRegistry leases,
            InboxPort inbox,
            boolean batchCheckpoint,
            int maxSubagentsPerTurn,
            KnowledgeSearchPort knowledge) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.knowledge = knowledge;
        Objects.requireNonNull(inbox, "inbox");
        List<MiddlewareBase> shared = new ArrayList<>(List.of(
                new PlatformContextMiddleware(),
                new PlatformTracingMiddleware(),
                new LeaseGuardMiddleware(leases),
                new RecoveryNoteMiddleware(),
                new InterruptCheckpointMiddleware(),
                new ToolSpanMiddleware(),
                new PolicyMiddleware(maxSubagentsPerTurn),
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
        // 前置检索只给主智能体：它带该智能体的知识库配置，按实例创建
        if (profile.subagent() == null
                && knowledge != null
                && profile.agent().knowledge().prefetch()) {
            withGuard.add(
                    new KnowledgePrefetchMiddleware(knowledge, profile.agent().knowledge()));
        }
        return List.copyOf(withGuard);
    }
}
