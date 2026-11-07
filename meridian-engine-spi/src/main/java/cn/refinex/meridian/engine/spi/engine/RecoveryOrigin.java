package cn.refinex.meridian.engine.spi.engine;

import java.util.Objects;

/**
 * 恢复说明的来源：本轮是在恢复哪一次失败的执行、以哪种方式恢复，见架构设计「崩溃恢复」。
 *
 * <p>Worker 认领一个曾经执行过的轮次（epoch 大于 1）时，先查本轮是否写过状态内容、写在哪次执行，再决定恢复方式
 * （ADR-0028）；恢复说明本身随 {@code TurnInput} 传入。
 *
 * @param failedEpoch 失败那次执行的租约 epoch，大于 0；steer 消息按 {@code consumed_epoch} 等于它重新投递
 * @param recoveryAttempt 轮次的 {@code recovery_attempt}：租约过期与可重试错误置回时加一，节点优雅下线不变，所以下线后
 *     续跑时为 0；认领时达到 2 的轮次不再执行，以 {@code stop_reason=error} 结束
 * @param mode 恢复方式；必填
 */
public record RecoveryOrigin(long failedEpoch, int recoveryAttempt, Mode mode) {

    /**
     * 创建恢复来源。
     *
     * @throws NullPointerException 缺少恢复方式时
     * @throws IllegalArgumentException epoch 或恢复次数不合法时
     */
    public RecoveryOrigin {
        Objects.requireNonNull(mode, "mode");
        if (failedEpoch < 1) {
            throw new IllegalArgumentException("failedEpoch 必须大于 0：" + failedEpoch);
        }
        if (recoveryAttempt < 0) {
            throw new IllegalArgumentException("recoveryAttempt 不能为负：" + recoveryAttempt);
        }
    }

    /** 恢复方式。 */
    public enum Mode {
        /** 本轮的任何一次执行都没有写入状态内容（进程被强杀）：重放完整输入。 */
        REPLAY_INPUT,
        /** 本轮写入过状态内容（出错、中断、停机时保存，或批次 checkpoint）：不重放输入，输入为 {@code RecoveryOnly}。 */
        RESUME_SAVED_STATE
    }
}
