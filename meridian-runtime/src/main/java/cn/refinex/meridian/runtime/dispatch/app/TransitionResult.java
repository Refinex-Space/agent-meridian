package cn.refinex.meridian.runtime.dispatch.app;

/** 执行方置回队列或结束轮次的结果。租约条件不满足不是异常，而是明确的结果（编码规范「SQL」）。 */
public sealed interface TransitionResult {

    /**
     * 已完成：轮次离开 {@code running}，租户并发计数已减一。
     *
     * @param turnId 轮次 ID
     * @param recoveryAttempt 更新后的恢复次数
     */
    record Done(String turnId, int recoveryAttempt) implements TransitionResult {}

    /**
     * 租约条件不满足（轮次已被回收或被其他节点认领），什么都没改。
     *
     * @param turnId 轮次 ID
     * @param epoch 执行方的租约 epoch
     */
    record LeaseLost(String turnId, long epoch) implements TransitionResult {}
}
