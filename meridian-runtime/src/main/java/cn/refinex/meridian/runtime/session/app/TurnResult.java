package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.runtime.dispatch.domain.RequeueReason;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;

/** 一次执行的结果：轮次结束、置回队列、放弃（失去租约或被取消），或 Worker 自身出错。 */
public sealed interface TurnResult {

    /**
     * 本次执行的轮次。
     *
     * @return 轮次 ID
     */
    String turnId();

    /**
     * 轮次已结束（终态或 {@code awaiting_action}），租户并发计数已减一。
     *
     * @param turnId 轮次 ID
     * @param outcome 轮次状态
     * @param stopReason 停止原因；不以空闲结束时为空
     */
    record Finished(String turnId, TurnOutcome outcome, StopReason stopReason) implements TurnResult {}

    /**
     * 轮次已置回队列。
     *
     * @param turnId 轮次 ID
     * @param reason 原因
     */
    record Requeued(String turnId, RequeueReason reason) implements TurnResult {}

    /**
     * 放弃本次执行，没有写入任何数据：失去租约（轮次已被回收或接管），或执行被取消（节点下线超时、模拟强杀）。
     * 轮次的终态由回收器或新的认领者给出。
     *
     * @param turnId 轮次 ID
     * @param cause 原因，用于日志
     */
    record Abandoned(String turnId, String cause) implements TurnResult {}

    /**
     * Worker 自身出错（例如数据库不可用），本次执行停止且没有收尾；租约到期后由回收器置回队列。
     *
     * @param turnId 轮次 ID
     * @param error 错误
     */
    record Failed(String turnId, Throwable error) implements TurnResult {}
}
