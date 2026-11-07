package cn.refinex.meridian.runtime.dispatch.domain;

import cn.refinex.meridian.engine.spi.event.SessionEvent.RescheduleReason;

/**
 * 轮次被置回队列的原因，按架构设计「置回队列的三种情况」区分 {@code recovery_attempt} 是否加一；三种情况租户并发计数都减一，
 * 都发出 {@code session.status_rescheduled}。
 */
public enum RequeueReason {
    /** 租约过期，由回收器处理：恢复次数加一。 */
    LEASE_EXPIRED(true, RescheduleReason.LEASE_EXPIRED),
    /** 可重试的错误（供应商 5xx、限流等待超时等）：恢复次数加一，此前已写入 {@code session.error(retryable)}（ADR-0026）。 */
    RETRYABLE_ERROR(true, RescheduleReason.RETRYABLE_ERROR),
    /** 节点优雅下线：恢复次数不变。 */
    NODE_SHUTDOWN(false, RescheduleReason.NODE_SHUTDOWN);

    private final boolean countsAsRecovery;
    private final RescheduleReason eventReason;

    RequeueReason(boolean countsAsRecovery, RescheduleReason eventReason) {
        this.countsAsRecovery = countsAsRecovery;
        this.eventReason = eventReason;
    }

    /**
     * 是否让 {@code recovery_attempt} 加一。
     *
     * @return 加一时为 true
     */
    public boolean countsAsRecovery() {
        return countsAsRecovery;
    }

    /**
     * 对应的 {@code session.status_rescheduled} 原因。
     *
     * @return 事件中的原因
     */
    public RescheduleReason eventReason() {
        return eventReason;
    }
}
