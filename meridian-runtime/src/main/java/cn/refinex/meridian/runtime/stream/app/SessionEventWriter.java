package cn.refinex.meridian.runtime.stream.app;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.runtime.dispatch.domain.NodeId;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.stream.domain.StatusChange;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.stream.domain.WriteFence;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import cn.refinex.meridian.runtime.stream.infra.system.SessionEventRepository;
import cn.refinex.meridian.runtime.stream.infra.system.SessionEventRepository.SeqAllocation;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 会话事件的唯一写入入口：Worker、API、回收器都经它写入 {@code rt.session_events}（架构设计「一轮执行的生命周期」）。
 *
 * <p>一个批次在一个事务里完成：
 * <ol>
 *   <li>租约条件（{@link WriteFence.TurnLease}）：锁住轮次行并校验 {@code status='running' AND lease_owner = 本节点
 *       AND lease_epoch = epoch}；不满足时返回 {@link AppendResult.LeaseLost}，什么都不写。
 *   <li>锁定会话行，{@code last_seq} 加上事件数，得到连续的 seq；会话行锁让同一会话的写入串行，seq 严格递增、不重复、不跳号。
 *   <li>插入事件；状态事件在同一事务中推进会话与线程的状态（{@link StatusChange}）。
 * </ol>
 * 事务传播为 REQUIRED：调用方已在事务中（例如回收器、置回队列）时加入调用方的事务，与调用方的其他写入一起提交或回滚。
 *
 * <p>事务提交后，写入的事件经 {@link EventStreamPublisher} 发布到 Redis Stream {@code ev:{session_id}}（不等待发布完成）。所有写入
 * 路径都经过这里，所以 Worker、回收器、置回队列与内核端口写入的事件都会被扇出；回滚的事务不发布；发布失败不影响已提交的事件，
 * 由 SSE 从数据库补发（架构设计「SSE 协议」，ADR-0028）。
 *
 * <p>方法会阻塞，runtime 中经 {@code Blocking} 调用。线程安全：无状态。
 */
@Service
public class SessionEventWriter {

    private final SessionEventRepository repository;
    private final TransactionTemplate transactions;
    private final NodeId nodeId;
    private final Clock clock;
    private final EventStreamPublisher publisher;

    /**
     * 创建写入入口。
     *
     * @param repository 事件仓储
     * @param transactions 事务模板（传播行为 REQUIRED）
     * @param nodeId 本节点 ID，租约条件中的 {@code lease_owner}
     * @param clock 时钟
     * @param publisher 提交后的实时扇出
     */
    public SessionEventWriter(
            SessionEventRepository repository,
            TransactionTemplate transactions,
            NodeId nodeId,
            Clock clock,
            EventStreamPublisher publisher) {
        this.repository = repository;
        this.transactions = transactions;
        this.nodeId = nodeId;
        this.clock = clock;
        this.publisher = publisher;
    }

    /**
     * 写入一批事件。
     *
     * @param batch 事件批次
     * @return 写入的事件，或租约丢失
     * @throws NotFoundException 会话不存在时
     */
    public AppendResult append(EventBatch batch) {
        return transactions.execute(status -> appendInTransaction(batch));
    }

    private AppendResult appendInTransaction(EventBatch batch) {
        if (batch.fence() instanceof WriteFence.TurnLease lease
                && !repository.lockLease(lease.turnId(), nodeId.value(), lease.epoch())) {
            return new AppendResult.LeaseLost(lease.turnId(), lease.epoch());
        }
        Instant now = clock.instant();
        SeqAllocation allocation = repository
                .allocate(batch.sessionId(), batch.events().size(), now)
                .orElseThrow(() -> new NotFoundException("会话不存在：" + batch.sessionId()));
        List<StoredEvent> stored = new ArrayList<>(batch.events().size());
        long seq = allocation.firstSeq();
        for (EngineEvent event : batch.events()) {
            StoredEvent row = new StoredEvent(
                    batch.sessionId(),
                    seq++,
                    Ids.newId(IdPrefix.EVENT),
                    allocation.tenantId(),
                    allocation.projectId(),
                    batch.threadId(),
                    batch.turnId(),
                    event.type(),
                    EngineJson.writeEvent(event),
                    now);
            repository.insert(row);
            StatusChange.of(batch.threadId(), event).ifPresent(change -> project(batch.sessionId(), change, now));
            stored.add(row);
        }
        publishAfterCommit(stored);
        return new AppendResult.Appended(stored);
    }

    private void publishAfterCommit(List<StoredEvent> stored) {
        List<StoredEvent> events = List.copyOf(stored);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 只订阅不等待：Redis 变慢或不可用时不拖住写入方，失败由发布器记录指标
                publisher.publish(events).subscribe();
            }
        });
    }

    private void project(String sessionId, StatusChange change, Instant now) {
        if (change.sessionStatus() != null) {
            repository.updateSessionStatus(sessionId, change.sessionStatus(), now);
        }
        if (change.threadId() != null) {
            repository.updateThreadStatus(sessionId, change.threadId(), change.threadStatus(), now);
        }
    }

    /**
     * 读取 {@code afterSeq} 之后的事件，按 seq 升序。
     *
     * @param sessionId 会话 ID
     * @param afterSeq 起点（不含）
     * @param limit 最多返回的条数
     * @return 事件
     */
    public List<StoredEvent> listAfter(String sessionId, long afterSeq, int limit) {
        return repository.listAfter(sessionId, afterSeq, limit);
    }

    /**
     * 读取一轮写入的指定类型的事件，按 seq 升序。
     *
     * @param sessionId 会话 ID
     * @param turnId 轮次 ID
     * @param types 事件类型
     * @return 事件
     */
    public List<StoredEvent> listByTurn(String sessionId, String turnId, List<EventType> types) {
        return repository.listByTurn(sessionId, turnId, types);
    }
}
