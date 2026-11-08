package cn.refinex.meridian.runtime.dispatch.app;

import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.runtime.dispatch.domain.ClaimedTurn;
import cn.refinex.meridian.runtime.dispatch.domain.NodeId;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnQueueRepository;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnQueueRepository.Candidate;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 认领一个轮次：严格按架构设计「认领」的三步 SQL，在一个独立事务里完成。
 *
 * <ol>
 *   <li>选出并锁住一个可执行的轮次（{@code FOR UPDATE OF t SKIP LOCKED}）：满额租户、未到最早认领时间、本节点仍持有的线程都被排除。
 *   <li>占用租户并发名额；更新 0 行说明被其他 Worker 抢先占满，回滚整个事务。
 *   <li>标记轮次为 {@code running}，epoch 加一，租约到期时间为当前时间加租约时长。
 * </ol>
 * 第 1 步之后、提交之前，在 {@link NodeLeaseRegistry} 中预留该线程的条目（{@code putIfAbsent}）；提交成功才激活，回滚或提交失败
 * 就取消预留。所以本节点同一线程任何时刻最多一个条目，上一次执行结束前不会被再次认领（架构设计「同节点重新认领」）。
 *
 * <p>方法会阻塞，runtime 中经 {@code Blocking} 调用。线程安全：可被多个 Worker 并发调用。
 */
@Service
public class TurnClaimer {

    private static final Logger log = LoggerFactory.getLogger(TurnClaimer.class);

    private final TurnQueueRepository queue;
    private final NodeLeaseRegistry registry;
    private final TransactionTemplate transactions;
    private final NodeId nodeId;
    private final DispatchProperties properties;
    private final Clock clock;

    /**
     * 创建认领用例。
     *
     * @param queue 轮次队列仓储
     * @param registry 本节点的租约登记
     * @param transactions 事务模板；认领总在新事务中执行，不加入调用方的事务
     * @param nodeId 本节点 ID，写入 {@code lease_owner}
     * @param properties 调度配置，取租约时长
     * @param clock 时钟
     */
    public TurnClaimer(
            TurnQueueRepository queue,
            NodeLeaseRegistry registry,
            TransactionTemplate transactions,
            NodeId nodeId,
            DispatchProperties properties,
            Clock clock) {
        this.queue = queue;
        this.registry = registry;
        this.transactions = new TransactionTemplate(transactions.getTransactionManager());
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.nodeId = nodeId;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 尝试认领一个轮次。
     *
     * @return 认领结果；{@link ClaimResult.Claimed} 时租约已登记到本节点
     */
    public ClaimResult claim() {
        Instant now = clock.instant();
        AtomicReference<LeaseHandle> reserved = new AtomicReference<>();
        AtomicReference<ClaimedTurn> claimed = new AtomicReference<>();
        ClaimResult result;
        try {
            result = transactions.execute(status -> {
                Optional<Candidate> candidate = queue.lockNextClaimable(now, registry.heldThreadIds());
                if (candidate.isEmpty()) {
                    return new ClaimResult.NoneAvailable();
                }
                Candidate turn = candidate.get();
                Optional<LeaseHandle> handle = registry.reserve(turn.threadId());
                if (handle.isEmpty()) {
                    status.setRollbackOnly();
                    return new ClaimResult.ThreadHeldOnNode(turn.threadId());
                }
                reserved.set(handle.get());
                if (!queue.acquireTenantSlot(turn.tenantId(), now)) {
                    status.setRollbackOnly();
                    return new ClaimResult.QuotaContended(turn.tenantId());
                }
                claimed.set(queue.markRunning(turn.turnId(), nodeId.value(), now, now.plus(properties.leaseTtl())));
                return new ClaimResult.Claimed(handle.get());
            });
        } catch (RuntimeException e) {
            cancel(reserved.get());
            throw e;
        }
        if (result instanceof ClaimResult.Claimed(LeaseHandle lease)) {
            lease.activate(claimed.get());
            ClaimedTurn turn = claimed.get();
            log.info(
                    "轮次已认领 turnId={} tenantId={} threadId={} epoch={} recoveryAttempt={}",
                    turn.turnId(),
                    turn.tenantId(),
                    turn.threadId(),
                    turn.epoch(),
                    turn.recoveryAttempt());
        } else {
            cancel(reserved.get());
        }
        return result;
    }

    /**
     * 读取认领到的轮次在 {@code turn_inputs} 中的完整输入。方法会阻塞。
     *
     * @param turn 认领到的轮次
     * @return 输入，类型由 {@code turns.kind} 决定
     * @throws IllegalStateException 轮次没有输入时（入队时与轮次同事务写入，不会发生）
     */
    public TurnInput input(ClaimedTurn turn) {
        String payload =
                queue.findInput(turn.turnId()).orElseThrow(() -> new IllegalStateException("轮次没有输入：" + turn.turnId()));
        return EngineJson.readInput(turn.kind(), payload);
    }

    /**
     * 按轮次 ID 读取轮次的输入，生成恢复轮时读取等待回应的那一轮的输入。
     *
     * @param turnId 轮次 ID
     * @return 输入
     * @throws IllegalStateException 轮次或其输入不存在时
     */
    public TurnInput input(String turnId) {
        TurnQueueRepository.StoredInput stored =
                queue.findStoredInput(turnId).orElseThrow(() -> new IllegalStateException("轮次没有输入：" + turnId));
        return EngineJson.readInput(stored.kind(), stored.payload());
    }

    private void cancel(LeaseHandle handle) {
        if (handle != null) {
            registry.cancel(handle);
        }
    }
}
