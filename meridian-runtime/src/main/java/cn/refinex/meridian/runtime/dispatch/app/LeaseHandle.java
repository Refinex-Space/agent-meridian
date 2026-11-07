package cn.refinex.meridian.runtime.dispatch.app;

import cn.refinex.meridian.runtime.dispatch.domain.ClaimedTurn;
import cn.refinex.meridian.runtime.dispatch.domain.LeaseLoss;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 本节点对一个线程的租约，由 {@link TurnClaimer} 在认领时登记到 {@link NodeLeaseRegistry}，交给执行方。
 *
 * <p>生命周期：认领事务中先预留（同一线程在本节点只能有一个条目），事务提交后激活；执行方在执行结束（含异常与取消）时
 * 调用 {@link #release()}，条目才被移除。所以本节点上一个线程的上一次执行没有结束之前，本节点不会再认领这个线程，
 * 旧执行的残余写入也不会借用新 epoch（架构设计「同节点重新认领」）。
 *
 * <p>续租结果经 {@link LeaseListener} 通知执行方；登记监听之前发生的中断请求与租约丢失会在登记时补发。
 *
 * <p>线程安全：状态字段为 volatile，监听与待补发的信号在本对象的监视器内维护，回调在监视器外执行。
 */
public final class LeaseHandle {

    private final NodeLeaseRegistry registry;
    private final String threadId;

    private volatile ClaimedTurn turn;
    private volatile boolean released;
    private volatile Instant lastRenewedAt;

    private LeaseListener listener;
    private Instant interruptRequestedAt;
    private boolean interruptDelivered;
    private LeaseLoss loss;
    private boolean lossDelivered;

    LeaseHandle(NodeLeaseRegistry registry, String threadId) {
        this.registry = registry;
        this.threadId = threadId;
    }

    /**
     * 返回认领到的轮次。
     *
     * @return 轮次
     * @throws IllegalStateException 认领事务尚未提交时
     */
    public ClaimedTurn turn() {
        ClaimedTurn current = turn;
        if (current == null) {
            throw new IllegalStateException("线程 " + threadId + " 的租约尚未激活");
        }
        return current;
    }

    /**
     * 返回持有租约的线程。
     *
     * @return 线程 ID
     */
    public String threadId() {
        return threadId;
    }

    /**
     * 返回本次执行的租约 epoch。
     *
     * @return epoch
     */
    public long epoch() {
        return turn().epoch();
    }

    /**
     * 登记续租结果的监听，并补发此前已发生的中断请求与租约丢失。只能登记一次。
     *
     * @param newListener 监听
     * @throws IllegalStateException 已登记过监听时
     */
    public void listen(LeaseListener newListener) {
        Objects.requireNonNull(newListener, "listener");
        Instant interrupt;
        LeaseLoss lost;
        synchronized (this) {
            if (listener != null) {
                throw new IllegalStateException("线程 " + threadId + " 的租约已登记过监听");
            }
            listener = newListener;
            interrupt = takeInterrupt();
            lost = takeLoss();
        }
        deliver(newListener, interrupt, lost);
    }

    /**
     * 返回租约丢失的原因。
     *
     * @return 原因；租约仍有效时为空
     */
    public synchronized Optional<LeaseLoss> loss() {
        return Optional.ofNullable(loss);
    }

    /**
     * 租约是否已经或即将失去。
     *
     * @return 续租发现租约丢失时为 true
     */
    public synchronized boolean isLost() {
        return loss != null;
    }

    /**
     * 执行结束（含异常与取消）时释放：从 {@link NodeLeaseRegistry} 移除条目与登记在它下面的子线程。重复调用无副作用。
     * 释放不改数据库：轮次的结束与置回队列由 {@link TurnTransitions} 完成。
     */
    public void release() {
        registry.release(this);
    }

    /**
     * 是否已经释放。
     *
     * @return 已释放时为 true
     */
    public boolean isReleased() {
        return released;
    }

    /** 认领事务提交后激活；认领前已有的中断请求作为待补发信号。 */
    void activate(ClaimedTurn claimed) {
        lastRenewedAt = claimed.claimedAt();
        turn = claimed;
        if (claimed.interruptRequestedAt() != null) {
            interruptRequested(claimed.interruptRequestedAt());
        }
    }

    boolean isActive() {
        return turn != null && !released;
    }

    void markReleased() {
        released = true;
    }

    Instant lastRenewedAt() {
        return lastRenewedAt;
    }

    void renewed(Instant at) {
        lastRenewedAt = at;
    }

    /** 记录中断请求，监听已登记时立即回调；每个租约只回调一次。 */
    void interruptRequested(Instant requestedAt) {
        LeaseListener target;
        Instant interrupt;
        synchronized (this) {
            if (interruptRequestedAt == null) {
                interruptRequestedAt = requestedAt;
            }
            target = listener;
            interrupt = target == null ? null : takeInterrupt();
        }
        deliver(target, interrupt, null);
    }

    /** 记录租约丢失，监听已登记时立即回调；每个租约只回调一次。 */
    void lost(LeaseLoss reason) {
        LeaseListener target;
        LeaseLoss lost;
        synchronized (this) {
            if (loss == null) {
                loss = reason;
            }
            target = listener;
            lost = target == null ? null : takeLoss();
        }
        deliver(target, null, lost);
    }

    private Instant takeInterrupt() {
        if (interruptRequestedAt == null || interruptDelivered) {
            return null;
        }
        interruptDelivered = true;
        return interruptRequestedAt;
    }

    private LeaseLoss takeLoss() {
        if (loss == null || lossDelivered) {
            return null;
        }
        lossDelivered = true;
        return loss;
    }

    private static void deliver(LeaseListener target, Instant interrupt, LeaseLoss lost) {
        if (target == null) {
            return;
        }
        if (interrupt != null) {
            target.onInterruptRequested(interrupt);
        }
        if (lost != null) {
            target.onLeaseLost(lost);
        }
    }
}
