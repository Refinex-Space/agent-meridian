package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.engine.AgentEngine;
import cn.refinex.meridian.engine.spi.engine.ThreadRef;
import cn.refinex.meridian.engine.spi.engine.ToolRetry;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote.Outcome;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote.RecoveredToolCall;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.error.RetryableTurnException;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.runtime.approval.app.ApprovalAudit;
import cn.refinex.meridian.runtime.approval.domain.ResumptionPlanner;
import cn.refinex.meridian.runtime.dispatch.app.LeaseHandle;
import cn.refinex.meridian.runtime.dispatch.app.LeaseListener;
import cn.refinex.meridian.runtime.dispatch.domain.ClaimedTurn;
import cn.refinex.meridian.runtime.dispatch.domain.LeaseLoss;
import cn.refinex.meridian.runtime.dispatch.domain.RequeueReason;
import cn.refinex.meridian.runtime.sandbox.app.SandboxService;
import cn.refinex.meridian.runtime.stream.app.EventStreamPublisher;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 本节点认领到的一轮的一次执行，按架构设计「一轮执行的生命周期」：
 *
 * <ol>
 *   <li>准备（{@link TurnPreparer}）：读取输入；恢复认领时先判定恢复方式；写 {@code session.status_running}。
 *   <li>{@code sandbox} 档先由 {@code SandboxService.prepare} 取得会话沙箱（可重试的失败自己写 {@code error(retryable)} 加
 *       {@code rescheduled}），终态之前 {@code afterTurn} 同步产物、计量 vCPU 秒（ADR-0037）。
 *   <li>调用 {@link AgentEngine#runTurn}。每个事件经租约条件写入 {@code session_events}，提交后由写入入口 XADD 到
 *       {@code ev:{session_id}}；只在流上的事件只发布。
 *   <li>终态事件到达后由 {@link TurnFinisher} 在一个事务里结束轮次（或置回队列）。
 *   <li>无论怎样结束（含取消），{@code finally} 中从 {@code LeaseRegistry} 移除本节点的条目。
 * </ol>
 *
 * <p>失去租约（续租发现、写入被拒、内核抛出 {@link LeaseLostException}）时只取消订阅，不写任何数据，<b>禁止</b>调用
 * {@code interrupt}：中断会触发 AgentScope 保存状态（架构设计「租约与 fencing」）。续租读到中断请求时调用
 * {@link AgentEngine#interrupt}；请求早于内核开始执行时，在收到内核的第一个事件后补发。
 *
 * <p>线程安全：{@link #run()} 只能订阅一次；{@link #cancel} 与续租回调可以在任意线程调用。
 */
public final class TurnExecution {

    private static final Logger log = LoggerFactory.getLogger(TurnExecution.class);

    private final LeaseHandle lease;
    private final ClaimedTurn turn;
    private final TurnPreparer preparer;
    private final TurnFinisher finisher;
    private final AgentEngine engine;
    private final ApprovalAudit audit;
    private final EventStreamPublisher publisher;
    private final TurnMetrics metrics;
    private final SandboxService sandboxes;
    private final Clock clock;
    private final Sinks.One<String> abandon = Sinks.one();
    private final AtomicReference<String> abandonCause = new AtomicReference<>();
    private final AtomicBoolean cancelledByNode = new AtomicBoolean();
    private final AtomicBoolean interruptRequested = new AtomicBoolean();
    private final AtomicBoolean interruptDelivered = new AtomicBoolean();
    private final AtomicBoolean engineStarted = new AtomicBoolean();
    private volatile String agentVersionId;

    TurnExecution(
            LeaseHandle lease,
            TurnPreparer preparer,
            TurnFinisher finisher,
            AgentEngine engine,
            ApprovalAudit audit,
            EventStreamPublisher publisher,
            TurnMetrics metrics,
            SandboxService sandboxes,
            Clock clock) {
        this.sandboxes = sandboxes;
        this.lease = lease;
        this.turn = lease.turn();
        this.preparer = preparer;
        this.finisher = finisher;
        this.engine = engine;
        this.audit = audit;
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * 认领到的轮次。
     *
     * @return 轮次
     */
    public ClaimedTurn turn() {
        return turn;
    }

    /**
     * 执行到结束。结束（含取消）时释放本节点的租约条目。不会以错误结束：Worker 自身的错误返回 {@link TurnResult.Failed}，
     * 这时没有任何收尾，租约到期后由回收器置回队列。
     *
     * @return 执行结果
     */
    public Mono<TurnResult> run() {
        Instant startedAt = clock.instant();
        // 时长在结果交给订阅方之前记录（调用方拿到结果时指标已经可读）；没有结果的取消在 doFinally 中补记，只记一次
        AtomicBoolean durationRecorded = new AtomicBoolean();
        Runnable recordDuration = () -> {
            if (durationRecorded.compareAndSet(false, true)) {
                metrics.executed(
                        agentVersionId == null ? "unknown" : agentVersionId,
                        Duration.between(startedAt, clock.instant()));
            }
        };
        return Mono.defer(() -> {
                    lease.listen(new Listener());
                    return Blocking.call(() -> preparer.prepare(turn)).flatMap(this::execute);
                })
                .takeUntilOther(abandon.asMono())
                .switchIfEmpty(Mono.defer(() -> Mono.just(abandoned())))
                .onErrorResume(this::failed)
                .doOnNext(this::countLeaseLoss)
                .doOnNext(result -> recordDuration.run())
                .contextWrite(PlatformContexts.write(baseContext()))
                .doFinally(signal -> {
                    lease.release();
                    recordDuration.run();
                });
    }

    /**
     * 放弃本次执行：取消订阅，不写任何数据。节点下线超时、或测试模拟进程被强杀时使用；失去租约由续租回调触发同样的处理。
     *
     * @param cause 原因，用于日志
     */
    public void cancel(String cause) {
        cancelledByNode.set(true);
        abandon(cause);
    }

    /**
     * 节点下线时把本轮置回队列（{@code recovery_attempt} 不变），然后放弃本次执行。轮次已经结束或已被置回时，置回是空操作。
     * 方法会阻塞。
     */
    void requeueForShutdown() {
        PlatformContexts.run(baseContext(), () -> finisher.requeue(turn, RequeueReason.NODE_SHUTDOWN));
        cancel("节点下线");
    }

    private Mono<TurnResult> execute(Prepared prepared) {
        return switch (prepared) {
            case Prepared.Done done -> {
                agentVersionId = done.agentVersionId();
                yield Mono.just(done.result());
            }
            case Prepared.Ready ready -> {
                agentVersionId = ready.agentVersionId();
                yield prepareSandbox(ready)
                        .flatMap(sandbox -> sandbox.failure() != null
                                ? rescheduleBeforeEngine(ready, sandbox.failure())
                                : runEngine(ready, sandbox.handle()));
            }
        };
    }

    private Mono<TurnResult> runEngine(Prepared.Ready ready, SandboxHandle sandbox) {
        return retried(ready)
                .flatMapMany(input -> engine.runTurn(ready.context(), input))
                .concatMap(event -> handle(ready, sandbox, event))
                .singleOrEmpty()
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("内核的事件流没有终态事件：" + turn.turnId())));
    }

    /**
     * {@code sandbox} 档：调用内核之前准备会话沙箱（架构设计「会话沙箱」"获取"）。沙箱基础设施暂时不可用时不调用内核，本轮以
     * {@code session.error(retryable)} 加 {@code status_rescheduled(retryable_error)} 置回队列，与内核遇到可重试错误时相同。
     */
    private Mono<SandboxPreparation> prepareSandbox(Prepared.Ready ready) {
        if (sandboxes == null) {
            return Mono.just(new SandboxPreparation(null, null));
        }
        return Blocking.call(() -> new SandboxPreparation(
                        sandboxes.prepare(ready.context()).orElse(null), null))
                .onErrorResume(RetryableTurnException.class, error -> {
                    log.warn(
                            "会话沙箱暂时不可用，本轮置回队列 turnId={} code={}",
                            turn.turnId(),
                            error.errorCode().code(),
                            error);
                    return Mono.just(new SandboxPreparation(null, error));
                });
    }

    private Mono<TurnResult> rescheduleBeforeEngine(Prepared.Ready ready, RetryableTurnException error) {
        String message = error.detail() == null || error.detail().isBlank()
                ? error.errorCode().title()
                : error.detail();
        return Flux.<EngineEvent>just(
                        new SessionEvent.Error(error.errorCode().code(), message, SessionEvent.RetryStatus.RETRYABLE),
                        new SessionEvent.StatusRescheduled(SessionEvent.RescheduleReason.RETRYABLE_ERROR))
                .concatMap(event -> handle(ready, null, event))
                .singleOrEmpty();
    }

    private Mono<TurnResult> handle(Prepared.Ready ready, SandboxHandle sandbox, EngineEvent event) {
        if (engineStarted.compareAndSet(false, true)) {
            deliverPendingInterrupt();
        }
        if (isTerminal(event)) {
            // 先收尾会话沙箱（产物同步、计量），再结束轮次：status_idle 发出时产物已在对象存储中
            Mono<Void> sandboxDone = sandbox == null
                    ? Mono.empty()
                    : Blocking.run(() -> sandboxes.afterTurn(
                            ready.context(), sandbox, !(event instanceof SessionEvent.StatusRescheduled)));
            return sandboxDone.then(Blocking.call(() -> finisher.finish(
                    turn, ready.agentVersionId(), ready.context().platform().interaction(), (SessionEvent) event)));
        }
        if (!event.type().isPersisted()) {
            // 只订阅不等待，与提交后发布的持久化事件在同一连接上按订阅顺序写入
            publisher
                    .publishTransient(turn.sessionId(), turn.threadId(), turn.turnId(), event)
                    .subscribe();
            return Mono.empty();
        }
        EventBatch batch = EventBatch.leased(turn.sessionId(), turn.threadId(), turn.turnId(), turn.epoch(), event);
        return Blocking.call(() -> audit.append(batch))
                .flatMap(result -> result instanceof AppendResult.LeaseLost
                        ? Mono.error(new LeaseLostException("写入事件时租约已失去：" + turn.turnId() + " epoch " + turn.epoch()))
                        : Mono.empty());
    }

    /**
     * 恢复确认选择 {@code retry} 的调用：平台用原幂等键依次重新执行，把结果摘要写进恢复说明中对应的 {@code RETRIED} 条目
     * （架构设计「待处理动作」）。没有要重试的调用时原样返回输入。
     */
    private Mono<TurnInput> retried(Prepared.Ready ready) {
        if (ready.retries().isEmpty() || ready.input().recovery() == null) {
            return Mono.just(ready.input());
        }
        return Flux.fromIterable(ready.retries())
                .concatMap(retry -> engine.retryToolCall(ready.context(), retry)
                        .map(result -> Map.entry(retry.toolUseId(), summary(result))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(summaries -> {
                    log.info("恢复确认的重试已执行 turnId={} calls={}", turn.turnId(), summaries.size());
                    List<RecoveredToolCall> calls = ready.input().recovery().toolCalls().stream()
                            .map(call -> call.outcome() == Outcome.RETRIED && summaries.containsKey(call.toolUseId())
                                    ? new RecoveredToolCall(
                                            call.toolUseId(),
                                            call.toolName(),
                                            Outcome.RETRIED,
                                            summaries.get(call.toolUseId()))
                                    : call)
                            .toList();
                    return ResumptionPlanner.withNote(ready.input(), new RecoveryNote(calls));
                });
    }

    private static String summary(ToolRetry.Result result) {
        String text = result.succeeded() ? result.text() : "重新执行失败：" + result.text();
        return text.length() <= TurnPreparer.SUMMARY_MAX_CHARS
                ? text
                : text.substring(0, TurnPreparer.SUMMARY_MAX_CHARS) + "…";
    }

    private void abandon(String cause) {
        abandonCause.compareAndSet(null, cause);
        abandon.tryEmitValue(cause);
    }

    private TurnResult abandoned() {
        String cause = abandonCause.get() == null ? "执行被取消" : abandonCause.get();
        log.warn("放弃本次执行，不写入任何数据 turnId={} epoch={} cause={}", turn.turnId(), turn.epoch(), cause);
        return new TurnResult.Abandoned(turn.turnId(), cause);
    }

    private Mono<TurnResult> failed(Throwable error) {
        if (causeOf(error, LeaseLostException.class) != null) {
            log.warn("租约已失去，放弃本次执行 turnId={} epoch={}", turn.turnId(), turn.epoch());
            return Mono.just(new TurnResult.Abandoned(turn.turnId(), "租约已失去"));
        }
        log.error("本次执行因 Worker 错误中止，等待租约到期后由回收器置回 turnId={} epoch={}", turn.turnId(), turn.epoch(), error);
        return Mono.just(new TurnResult.Failed(turn.turnId(), error));
    }

    private void countLeaseLoss(TurnResult result) {
        if (result instanceof TurnResult.Abandoned && !cancelledByNode.get()) {
            metrics.leaseLost();
        }
    }

    private void deliverPendingInterrupt() {
        if (interruptRequested.get() && !interruptDelivered.get() && engine.interrupt(threadRef())) {
            interruptDelivered.set(true);
        }
    }

    private ThreadRef threadRef() {
        return new ThreadRef(turn.sessionId(), turn.threadId());
    }

    private PlatformContext baseContext() {
        return PlatformContext.builder()
                .tenantId(turn.tenantId())
                .projectId(turn.projectId())
                .sessionId(turn.sessionId())
                .threadId(turn.threadId())
                .turnId(turn.turnId())
                .leaseEpoch(turn.epoch())
                .build();
    }

    private static boolean isTerminal(EngineEvent event) {
        return event instanceof SessionEvent.StatusIdle
                || event instanceof SessionEvent.StatusRequiresAction
                || event instanceof SessionEvent.StatusRescheduled;
    }

    private static <T extends Throwable> T causeOf(Throwable error, Class<T> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }

    /**
     * 准备会话沙箱的结果。
     *
     * @param handle 本轮的会话沙箱；{@code none} 档或失败时为 null
     * @param failure 可重试的失败；成功时为 null
     */
    private record SandboxPreparation(SandboxHandle handle, RetryableTurnException failure) {}

    /** 续租结果的回调：在续租任务的虚拟线程上执行，只做不阻塞的操作。 */
    private final class Listener implements LeaseListener {

        @Override
        public void onInterruptRequested(Instant requestedAt) {
            log.info("收到中断请求 turnId={} requestedAt={}", turn.turnId(), requestedAt);
            interruptRequested.set(true);
            if (engineStarted.get()) {
                deliverPendingInterrupt();
            }
        }

        @Override
        public void onLeaseLost(LeaseLoss loss) {
            // 只取消订阅，禁止 interrupt：中断会让 AgentScope 保存状态
            abandon("租约丢失：" + loss);
        }
    }
}
