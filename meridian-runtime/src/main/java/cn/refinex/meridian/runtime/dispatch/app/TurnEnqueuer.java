package cn.refinex.meridian.runtime.dispatch.app;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnQueueRepository;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 轮次入队：在一个事务里写租户并发计数行、轮次与完整输入，提交后发布唤醒通知（架构设计「一轮执行的生命周期」）。
 *
 * <p>三者同事务写入，所以不会出现无法认领的"毒丸"：没有计数行的轮次会被认领 SQL 的关联条件永远排除，没有输入的轮次
 * 无法执行，而它们都会借部分唯一索引占住线程。同一线程的并发入队只有一个成功，其余返回 {@link EnqueueResult.ThreadBusy}。
 *
 * <p>事务传播为 REQUIRED：API 在写事件、收件箱的同一事务中入队时，唤醒通知在外层事务提交后才发布，Worker 被唤醒时一定能看到新轮次。
 * 方法会阻塞，runtime 中经 {@code Blocking} 调用。
 */
@Service
public class TurnEnqueuer {

    private final TurnQueueRepository queue;
    private final TransactionTemplate transactions;
    private final WakeupPublisher wakeups;
    private final DispatchProperties properties;
    private final Clock clock;

    /**
     * 创建入队用例。
     *
     * @param queue 轮次队列仓储
     * @param transactions 事务模板（传播行为 REQUIRED）
     * @param wakeups 唤醒通知
     * @param properties 调度配置，取新租户的并发上限
     * @param clock 时钟
     */
    public TurnEnqueuer(
            TurnQueueRepository queue,
            TransactionTemplate transactions,
            WakeupPublisher wakeups,
            DispatchProperties properties,
            Clock clock) {
        this.queue = queue;
        this.transactions = transactions;
        this.wakeups = wakeups;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 入队一个轮次。
     *
     * @param turn 入队参数
     * @return 新轮次的 ID，或线程已有未结束的轮次
     */
    public EnqueueResult enqueue(NewTurn turn) {
        return transactions.execute(status -> {
            Instant now = clock.instant();
            // 先写计数行再写轮次：与同一线程、同一租户的并发入队保持相同的加锁顺序
            queue.ensureTenantCounter(turn.tenantId(), properties.defaultTenantMaxRunning(), now);
            String turnId = Ids.newId(IdPrefix.TURN);
            if (!queue.insertQueued(turnId, turn, now)) {
                return new EnqueueResult.ThreadBusy(turn.threadId());
            }
            queue.insertInput(turnId, turn, EngineJson.writeInput(turn.input()), now);
            publishAfterCommit();
            return new EnqueueResult.Enqueued(turnId);
        });
    }

    private void publishAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                wakeups.publish();
            }
        });
    }
}
