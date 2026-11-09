package cn.refinex.meridian.runtime.dispatch.app;

import cn.refinex.meridian.runtime.dispatch.domain.ClaimedTurn;
import cn.refinex.meridian.runtime.dispatch.domain.LeaseLoss;
import cn.refinex.meridian.runtime.dispatch.domain.NodeId;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnLeaseRepository;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnLeaseRepository.Renewal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 续租：每 10 秒（{@code DispatchProperties.renewInterval}）为本节点的每个租约带 epoch 条件推迟到期时间，同时读出中断请求
 * （架构设计「租约」）。结果经 {@link LeaseHandle} 通知执行方：
 *
 * <ul>
 *   <li>读到 {@code interrupt_requested_at}：回调 {@link LeaseListener#onInterruptRequested}，数据库字段为准、Pub/Sub 只是加速，
 *       所以通知丢失时最多晚一个续租周期（架构设计「消息投递」中的「中断」）。
 *   <li>更新 0 行：租约确定已失去，回调 {@link LeaseListener#onLeaseLost}（{@link LeaseLoss#REVOKED}）。
 *   <li>续租抛出异常（例如数据库不可用）：距上次成功续租超过 15 秒（{@code renewFailureTimeout}）时回调
 *       {@link LeaseLoss#RENEWAL_TIMEOUT}，执行方在租约到期之前停下，不与新的持有者并行执行。
 * </ul>
 * 时间取注入的 {@code Clock}。每个租约单独处理，一个失败不影响其他。
 *
 * <p>每次续租在自己的事务里执行，并设 {@link #RENEW_LOCK_TIMEOUT} 的锁等待上限：轮次行可能被长时间锁住（发送事件的事务锁住轮次行后
 * 等待被沙箱准备占住的会话行），不设上限时续租的 {@code UPDATE} 会一直等，单线程的续租循环随之停住，本节点全部租约在 30 秒后
 * 被回收、在别的节点重复执行。设了上限，被锁住的那个租约按"续租抛出异常"处理，其他租约照常续租（R1 并发审查，ADR-0041）。
 */
@Service
public class LeaseRenewer {

    private static final Logger log = LoggerFactory.getLogger(LeaseRenewer.class);

    /**
     * 单次续租等待行锁的上限。带租约条件的写入以 {@code FOR SHARE} 锁轮次行、持锁只到事务结束（毫秒级），2 秒足以等过它们；
     * 远小于续租周期（10 秒）与失败阈值（15 秒），一个租约被锁住不会拖住其他租约的续租。
     */
    static final Duration RENEW_LOCK_TIMEOUT = Duration.ofSeconds(2);

    private final TurnLeaseRepository leases;
    private final TransactionTemplate transactions;
    private final NodeLeaseRegistry registry;
    private final NodeId nodeId;
    private final DispatchProperties properties;
    private final Clock clock;

    /**
     * 创建续租任务。
     *
     * @param leases 租约仓储
     * @param transactions 事务模板；每次续租在新事务中执行
     * @param registry 本节点的租约登记
     * @param nodeId 本节点 ID
     * @param properties 调度配置，取租约时长与续租失败阈值
     * @param clock 时钟
     */
    public LeaseRenewer(
            TurnLeaseRepository leases,
            TransactionTemplate transactions,
            NodeLeaseRegistry registry,
            NodeId nodeId,
            DispatchProperties properties,
            Clock clock) {
        this.leases = leases;
        this.transactions = new TransactionTemplate(transactions.getTransactionManager());
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.registry = registry;
        this.nodeId = nodeId;
        this.properties = properties;
        this.clock = clock;
    }

    /** 为本节点全部有效的租约续租一次。 */
    public void renewAll() {
        for (LeaseHandle handle : registry.activeHandles()) {
            if (!handle.isLost()) {
                renew(handle);
            }
        }
    }

    /**
     * 立即为一个线程续租一次，收到节点频道的通知时调用（{@link NodeChannelListener}）：读出中断请求，或发现租约已失去。本节点没有
     * 该线程的有效租约时什么都不做。
     *
     * @param threadId 线程 ID
     */
    public void renewThread(String threadId) {
        registry.handle(threadId)
                .filter(handle -> handle.isActive() && !handle.isLost())
                .ifPresent(this::renew);
    }

    private void renew(LeaseHandle handle) {
        ClaimedTurn turn = handle.turn();
        Instant now = clock.instant();
        Optional<Renewal> renewal;
        try {
            renewal = transactions.execute(status -> {
                leases.limitLockWait(RENEW_LOCK_TIMEOUT);
                return leases.renew(turn.turnId(), nodeId.value(), turn.epoch(), now.plus(properties.leaseTtl()), now);
            });
        } catch (RuntimeException e) {
            Duration sinceRenewed = Duration.between(handle.lastRenewedAt(), now);
            if (sinceRenewed.compareTo(properties.renewFailureTimeout()) > 0) {
                log.error(
                        "续租连续失败超过阈值，通知执行方取消 turnId={} epoch={} sinceRenewed={}",
                        turn.turnId(),
                        turn.epoch(),
                        sinceRenewed,
                        e);
                handle.lost(LeaseLoss.RENEWAL_TIMEOUT);
            } else {
                log.warn("续租失败，下个周期重试 turnId={} epoch={}", turn.turnId(), turn.epoch(), e);
            }
            return;
        }
        if (renewal.isEmpty()) {
            log.warn("续租时发现租约已失去 turnId={} epoch={}", turn.turnId(), turn.epoch());
            handle.lost(LeaseLoss.REVOKED);
            return;
        }
        handle.renewed(now);
        if (renewal.get().interruptRequestedAt() != null) {
            handle.interruptRequested(renewal.get().interruptRequestedAt());
        }
    }
}
