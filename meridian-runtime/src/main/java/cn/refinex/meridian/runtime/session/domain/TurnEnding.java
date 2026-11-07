package cn.refinex.meridian.runtime.session.domain;

import cn.refinex.meridian.engine.spi.event.SessionEvent.RescheduleReason;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.runtime.dispatch.domain.RequeueReason;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;

/**
 * 轮次结束时的映射规则（架构设计「状态机」「置回队列的三种情况」，ADR-0028）。
 *
 * <ul>
 *   <li>{@code session.status_idle} 的停止原因决定轮次状态：{@code interrupted} 为 {@code interrupted}，{@code error} 为
 *       {@code failed}，其余（正常结束、达到最大推理次数、工具全部被拒绝、预算不足）为 {@code completed}。
 *   <li>{@code session.status_rescheduled} 的原因决定置回队列的原因，进而决定 {@code recovery_attempt} 是否加一。
 * </ul>
 */
public final class TurnEnding {

    private TurnEnding() {}

    /**
     * 以空闲结束的轮次进入的状态。
     *
     * @param stopReason 停止原因
     * @return 轮次状态
     */
    public static TurnOutcome outcomeOf(StopReason stopReason) {
        return switch (stopReason) {
            case INTERRUPTED -> TurnOutcome.INTERRUPTED;
            case ERROR -> TurnOutcome.FAILED;
            case END_TURN, MAX_TURNS, BUDGET_EXCEEDED, TOOLS_DENIED -> TurnOutcome.COMPLETED;
        };
    }

    /**
     * 内核以 {@code session.status_rescheduled} 结束时置回队列的原因（ADR-0026：可重试错误与节点下线）。
     *
     * @param reason 事件中的原因
     * @return 置回队列的原因
     */
    public static RequeueReason requeueReasonOf(RescheduleReason reason) {
        return switch (reason) {
            case LEASE_EXPIRED -> RequeueReason.LEASE_EXPIRED;
            case NODE_SHUTDOWN -> RequeueReason.NODE_SHUTDOWN;
            case RETRYABLE_ERROR -> RequeueReason.RETRYABLE_ERROR;
        };
    }
}
