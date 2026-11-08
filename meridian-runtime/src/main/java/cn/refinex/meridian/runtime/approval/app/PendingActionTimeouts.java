package cn.refinex.meridian.runtime.approval.app;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import cn.refinex.meridian.runtime.approval.infra.PendingActionRepository;
import cn.refinex.meridian.runtime.approval.infra.system.PendingActionTimeoutRepository;
import cn.refinex.meridian.runtime.approval.infra.system.PendingActionTimeoutRepository.DueAction;
import cn.refinex.meridian.runtime.session.infra.system.ThreadLockRepository;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 待处理动作的超时（架构设计「待处理动作」，默认 30 分钟，{@code meridian.runtime.worker.pending-action-timeout}）：每个节点定期
 * 扫描，逐个在一个事务里处理：按类型生成结论（工具确认视为拒绝、自定义工具以 {@code timeout} 错误回传、恢复确认视为 skip），
 * 一轮的全部动作都有结论时生成恢复轮。
 *
 * <p>多节点：动作行以 {@code SKIP LOCKED} 锁住，同一个动作只被一个节点处理。随后不等待地尝试取线程的咨询锁：发送事件与结束轮次
 * 的事务先取咨询锁再锁动作行，这里反过来，所以只尝试、拿不到就留到下一次扫描，避免死锁。
 *
 * <p>线程安全：无状态。方法会阻塞，在后台任务的虚拟线程上调用。
 */
@Service
public class PendingActionTimeouts {

    private static final Logger log = LoggerFactory.getLogger(PendingActionTimeouts.class);

    private final PendingActionTimeoutRepository timeouts;
    private final PendingActionRepository actions;
    private final ThreadLockRepository threadLocks;
    private final PendingActionResolver resolver;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建超时处理。
     *
     * @param timeouts 超时扫描仓储
     * @param actions 待处理动作仓储
     * @param threadLocks 线程咨询锁
     * @param resolver 待处理动作的结论与恢复轮
     * @param transactions 事务模板（传播行为 REQUIRED）
     * @param clock 时钟
     */
    public PendingActionTimeouts(
            PendingActionTimeoutRepository timeouts,
            PendingActionRepository actions,
            ThreadLockRepository threadLocks,
            PendingActionResolver resolver,
            TransactionTemplate transactions,
            Clock clock) {
        this.timeouts = timeouts;
        this.actions = actions;
        this.threadLocks = threadLocks;
        this.resolver = resolver;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 处理当前全部已超时的动作。
     *
     * @return 处理的动作数
     */
    public int expireDue() {
        Set<String> skipped = new HashSet<>();
        int expired = 0;
        while (true) {
            Optional<Outcome> outcome = transactions.execute(status -> expireNext(skipped));
            if (outcome.isEmpty()) {
                return expired;
            }
            if (outcome.get() == Outcome.EXPIRED) {
                expired++;
            }
        }
    }

    private Optional<Outcome> expireNext(Set<String> skipped) {
        Optional<DueAction> next = timeouts.lockNextDue(clock.instant(), skipped);
        if (next.isEmpty()) {
            return Optional.empty();
        }
        DueAction due = next.get();
        if (!threadLocks.tryLock(due.threadId())) {
            log.debug("线程正被其他事务锁住，下次扫描再处理超时 actionId={}", due.actionId());
            skipped.add(due.actionId());
            return Optional.of(Outcome.SKIPPED);
        }
        PlatformContext context = PlatformContext.builder()
                .tenantId(due.tenantId())
                .projectId(due.projectId())
                .sessionId(due.sessionId())
                .build();
        return Optional.of(PlatformContexts.call(context, () -> {
            List<StoredPendingAction> locked = actions.lockAll(due.sessionId(), List.of(due.actionId()));
            if (locked.isEmpty() || !resolver.expire(locked.getFirst())) {
                return Outcome.SKIPPED;
            }
            resolver.resumeIfComplete(due.sessionId(), due.threadId(), due.turnId());
            return Outcome.EXPIRED;
        }));
    }

    /** 一个动作的处理结果。 */
    private enum Outcome {
        /** 已生成超时结论。 */
        EXPIRED,
        /** 跳过：线程正被锁住，或动作已不在等待。 */
        SKIPPED
    }
}
