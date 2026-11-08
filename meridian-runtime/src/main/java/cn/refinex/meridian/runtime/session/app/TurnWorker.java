package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.engine.spi.engine.AgentEngine;
import cn.refinex.meridian.runtime.approval.app.ApprovalAudit;
import cn.refinex.meridian.runtime.dispatch.app.ClaimResult;
import cn.refinex.meridian.runtime.dispatch.app.TurnClaimer;
import cn.refinex.meridian.runtime.sandbox.app.SandboxService;
import cn.refinex.meridian.runtime.stream.app.EventStreamPublisher;
import java.time.Clock;
import java.util.Optional;

/**
 * 轮次 Worker：认领一个轮次，交给 {@link TurnExecution} 执行（架构设计「一轮执行的生命周期」）。后台循环由
 * {@link TurnWorkerLifecycle} 驱动；测试直接调用 {@link #claim()} 与 {@link TurnExecution#run()}，在同一 JVM 中用不同节点 ID 的
 * 多个 Worker 模拟多节点。
 *
 * <p>只经 {@link AgentEngine} 接口使用内核（ArchUnit 规则 2），引擎实例的获取与引用计数在 {@code runTurn} 内部完成。
 *
 * <p>线程安全：无状态，可并发认领。
 */
public final class TurnWorker {

    /** 一次 {@link #claim()} 中因竞争失败（租户名额被抢、线程仍在本节点）而立即重试的上限。 */
    private static final int MAX_CLAIM_ATTEMPTS = 5;

    private final TurnClaimer claimer;
    private final TurnPreparer preparer;
    private final TurnFinisher finisher;
    private final AgentEngine engine;
    private final ApprovalAudit audit;
    private final EventStreamPublisher publisher;
    private final TurnMetrics metrics;
    private final SandboxService sandboxes;
    private final Clock clock;

    /**
     * 创建 Worker。
     *
     * @param claimer 认领用例
     * @param preparer 准备用例
     * @param finisher 收尾用例
     * @param engine 智能体内核
     * @param audit 事件写入与权限判定审计
     * @param publisher 只在流上的事件的发布
     * @param metrics 指标
     * @param clock 时钟
     */
    public TurnWorker(
            TurnClaimer claimer,
            TurnPreparer preparer,
            TurnFinisher finisher,
            AgentEngine engine,
            ApprovalAudit audit,
            EventStreamPublisher publisher,
            TurnMetrics metrics,
            Clock clock) {
        this(claimer, preparer, finisher, engine, audit, publisher, metrics, null, clock);
    }

    /**
     * 创建支持 {@code sandbox} 档的 Worker：轮次开始前准备会话沙箱，结束时同步产物与计量（T12a）。
     *
     * @param claimer 认领用例
     * @param preparer 准备用例
     * @param finisher 收尾用例
     * @param engine 智能体内核
     * @param audit 事件写入与权限判定审计
     * @param publisher 只在流上的事件的发布
     * @param metrics 指标
     * @param sandboxes 会话沙箱服务；为 null 时不准备沙箱，{@code sandbox} 档的轮次由内核以错误结束
     * @param clock 时钟
     */
    public TurnWorker(
            TurnClaimer claimer,
            TurnPreparer preparer,
            TurnFinisher finisher,
            AgentEngine engine,
            ApprovalAudit audit,
            EventStreamPublisher publisher,
            TurnMetrics metrics,
            SandboxService sandboxes,
            Clock clock) {
        this.sandboxes = sandboxes;
        this.claimer = claimer;
        this.preparer = preparer;
        this.finisher = finisher;
        this.engine = engine;
        this.audit = audit;
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * 认领一个轮次。方法会阻塞。
     *
     * @return 待执行的轮次；没有可执行的轮次时为空
     */
    public Optional<TurnExecution> claim() {
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            switch (claimer.claim()) {
                case ClaimResult.Claimed claimed -> {
                    metrics.claimed(claimed.lease().turn());
                    return Optional.of(new TurnExecution(
                            claimed.lease(), preparer, finisher, engine, audit, publisher, metrics, sandboxes, clock));
                }
                case ClaimResult.NoneAvailable _ -> {
                    return Optional.empty();
                }
                case ClaimResult.QuotaContended _, ClaimResult.ThreadHeldOnNode _ -> {
                    // 竞争失败，事务已回滚，立即再试
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 智能体内核，节点下线时使用。
     *
     * @return 内核
     */
    AgentEngine engine() {
        return engine;
    }
}
