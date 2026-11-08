package cn.refinex.meridian.runtime.dispatch.app;

import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.runtime.dispatch.domain.ClaimedTurn;
import cn.refinex.meridian.runtime.dispatch.domain.NodeId;
import cn.refinex.meridian.runtime.dispatch.domain.RequeueReason;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnLeaseRepository;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnLeaseRepository.ActiveTurn;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnLeaseRepository.ExpiredLease;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnLeaseRepository.ReleasedTurn;
import cn.refinex.meridian.runtime.stream.app.SessionEventWriter;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.stream.domain.WriteFence;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 轮次离开 {@code running} 的状态迁移：置回队列（三种原因）与结束，每次迁移在一个事务里完成。
 *
 * <p>置回队列按架构设计「置回队列的三种情况」：轮次回到 {@code queued}，租户并发计数一律减一，{@code recovery_attempt} 按原因
 * 决定是否加一，并写入 {@code session.status_rescheduled}（会话与线程随之进入 {@code rescheduled}）。执行方置回或结束时以自己的
 * 租约为条件（节点与 epoch），回收器以过期租约的 epoch 为条件；条件本身就是 fencing，所以同一事务里的事件不再附加租约条件。
 *
 * <p>事务传播为 REQUIRED：T06 的 Worker 可以把终态事件、结束轮次、写 outbox 放进同一个事务。方法会阻塞，runtime 中经
 * {@code Blocking} 调用。
 */
@Service
public class TurnTransitions {

    private static final Logger log = LoggerFactory.getLogger(TurnTransitions.class);

    private final TurnLeaseRepository leases;
    private final SessionEventWriter events;
    private final TransactionTemplate transactions;
    private final NodeId nodeId;
    private final Clock clock;

    /**
     * 创建状态迁移用例。
     *
     * @param leases 租约仓储
     * @param events 事件写入入口
     * @param transactions 事务模板（传播行为 REQUIRED）
     * @param nodeId 本节点 ID
     * @param clock 时钟
     */
    public TurnTransitions(
            TurnLeaseRepository leases,
            SessionEventWriter events,
            TransactionTemplate transactions,
            NodeId nodeId,
            Clock clock) {
        this.leases = leases;
        this.events = events;
        this.transactions = transactions;
        this.nodeId = nodeId;
        this.clock = clock;
    }

    /**
     * 执行方把自己的轮次置回队列：可重试错误（ADR-0026，此前已写入 {@code session.error(retryable)}）或节点优雅下线。
     *
     * @param turn 本节点认领的轮次
     * @param reason 原因；租约过期由回收器处理，这里也接受，用于执行方自己发现租约将过期的情况
     * @return 结果
     */
    public TransitionResult requeue(ClaimedTurn turn, RequeueReason reason) {
        return transactions.execute(status -> {
            Instant now = clock.instant();
            Optional<ReleasedTurn> released =
                    leases.requeueOwned(turn.turnId(), nodeId.value(), turn.epoch(), reason.countsAsRecovery(), now);
            if (released.isEmpty()) {
                return new TransitionResult.LeaseLost(turn.turnId(), turn.epoch());
            }
            afterRequeue(released.get(), reason, now);
            log.info(
                    "轮次已置回队列 turnId={} reason={} epoch={} recoveryAttempt={}",
                    turn.turnId(),
                    reason,
                    turn.epoch(),
                    released.get().recoveryAttempt());
            return new TransitionResult.Done(turn.turnId(), released.get().recoveryAttempt());
        });
    }

    /**
     * 执行方结束轮次，租户并发计数减一。终态事件由调用方经 {@link SessionEventWriter} 写入，可以与本方法放在同一个事务里。
     *
     * @param turn 本节点认领的轮次
     * @param outcome 结束后的状态
     * @param stopReason 停止原因；可为空，表示不以 {@code session.status_idle} 结束
     * @return 结果
     */
    public TransitionResult finish(ClaimedTurn turn, TurnOutcome outcome, StopReason stopReason) {
        return transactions.execute(status -> {
            Instant now = clock.instant();
            Optional<ReleasedTurn> released =
                    leases.finish(turn.turnId(), nodeId.value(), turn.epoch(), outcome, stopReason, now);
            if (released.isEmpty()) {
                return new TransitionResult.LeaseLost(turn.turnId(), turn.epoch());
            }
            releaseSlot(released.get(), now);
            return new TransitionResult.Done(turn.turnId(), released.get().recoveryAttempt());
        });
    }

    /**
     * 回收一个租约过期的轮次：锁住、置回队列、恢复次数加一、租户并发计数减一、写 {@code session.status_rescheduled}，
     * 全部在一个事务里（架构设计「租约回收」）。
     *
     * @return 被回收的轮次；没有过期的租约时为空
     */
    Optional<ReleasedTurn> reapNextExpired() {
        return transactions.execute(status -> {
            Instant now = clock.instant();
            Optional<ExpiredLease> expired = leases.lockNextExpired(now);
            if (expired.isEmpty()) {
                return Optional.<ReleasedTurn>empty();
            }
            ExpiredLease lease = expired.get();
            Optional<ReleasedTurn> released = leases.requeueExpired(lease.turnId(), lease.epoch(), now);
            released.ifPresent(turn -> {
                afterRequeue(turn, RequeueReason.LEASE_EXPIRED, now);
                log.warn(
                        "租约过期，轮次已置回队列 turnId={} leaseOwner={} epoch={} expiredAt={} recoveryAttempt={}",
                        turn.turnId(),
                        lease.leaseOwner(),
                        lease.epoch(),
                        lease.leaseExpiresAt(),
                        turn.recoveryAttempt());
            });
            return released;
        });
    }

    private void afterRequeue(ReleasedTurn turn, RequeueReason reason, Instant now) {
        releaseSlot(turn, now);
        events.append(new EventBatch(
                turn.sessionId(),
                turn.threadId(),
                turn.turnId(),
                WriteFence.none(),
                List.of(new SessionEvent.StatusRescheduled(reason.eventReason()))));
    }

    /**
     * 锁定会话的全部未结束轮次，返回它们的状态（见 {@link TurnLeaseRepository#lockActive}）。必须在调用方的事务中调用。
     *
     * @param sessionId 会话 ID
     * @return 未结束的轮次
     */
    public List<ActiveTurn> lockActive(String sessionId) {
        return leases.lockActive(sessionId);
    }

    /**
     * 锁定线程的未结束轮次（见 {@link TurnLeaseRepository#lockActiveOnThread}）。必须在调用方的事务中调用。
     *
     * @param threadId 线程 ID
     * @return 未结束的轮次；线程空闲时为空
     */
    public Optional<ActiveTurn> lockActiveOnThread(String threadId) {
        return leases.lockActiveOnThread(threadId);
    }

    /**
     * 记录轮次的中断请求（{@code turns.interrupt_requested_at}）：执行中的轮次由持有租约的节点在续租时读到，排队中的恢复轮在
     * 认领时读到（架构设计「消息投递」）。必须在调用方的事务中调用，轮次已由 {@link #lockActiveOnThread} 锁住。
     *
     * @param turnId 轮次 ID
     * @return 轮次仍在排队或执行时为 true
     */
    public boolean requestInterrupt(String turnId) {
        return leases.requestInterrupt(turnId, clock.instant());
    }

    /**
     * 取消排队中的轮次（排队时收到中断）。必须在调用方的事务中调用，轮次已由 {@link #lockActiveOnThread} 锁住。
     *
     * @param turnId 轮次 ID
     * @return 轮次仍在排队时为 true
     */
    public boolean cancelQueued(String turnId) {
        return leases.cancelQueued(turnId, clock.instant());
    }

    /**
     * 等待回应的轮次结束为 {@code completed}：它的全部待处理动作都有了结论，恢复轮随后入队。必须在调用方的事务中调用。
     *
     * @param turnId 轮次 ID
     * @return 轮次仍在等待回应时为 true
     */
    public boolean completeAwaiting(String turnId) {
        return leases.completeAwaiting(turnId, clock.instant());
    }

    /**
     * 取消会话的全部未结束轮次，执行中的轮次释放租户并发名额（会话归档与删除，架构设计「状态机」）。事务传播为 REQUIRED，
     * 与调用方随后写入的 {@code session.status_terminated} 一起提交。
     *
     * @param sessionId 会话 ID
     * @return 被取消的轮次
     */
    public List<ActiveTurn> cancelActive(String sessionId) {
        return transactions.execute(status -> {
            Instant now = clock.instant();
            List<ActiveTurn> cancelled = leases.cancelActive(sessionId, now);
            for (ActiveTurn turn : cancelled) {
                if (turn.running() && !leases.releaseTenantSlot(turn.tenantId(), now)) {
                    log.error("释放租户并发名额时计数已为 0 tenantId={} turnId={}", turn.tenantId(), turn.turnId());
                }
                log.info(
                        "会话终止，轮次已取消 sessionId={} turnId={} previousStatus={}", sessionId, turn.turnId(), turn.status());
            }
            return cancelled;
        });
    }

    private void releaseSlot(ReleasedTurn turn, Instant now) {
        if (!leases.releaseTenantSlot(turn.tenantId(), now)) {
            // 计数与轮次不一致是缺陷：不让计数变负、不阻塞状态迁移，只告警
            log.error("释放租户并发名额时计数已为 0 tenantId={} turnId={}", turn.tenantId(), turn.turnId());
        }
    }
}
