package cn.refinex.meridian.engine.spi.engine;

import java.util.Objects;

/**
 * 恢复说明的来源：本轮是在恢复哪一次失败的执行、以哪种方式恢复，见架构设计「崩溃恢复」。
 *
 * <p>Worker 认领一个被置回队列的轮次时，先比较线程状态的 {@code content_epoch} 与失败那次执行的
 * {@code lease_epoch}，再决定恢复方式；恢复说明本身随 {@code TurnInput} 传入。
 *
 * @param failedEpoch 失败那次执行的租约 epoch，大于 0；steer 消息按 {@code consumed_epoch} 等于它重新投递
 * @param recoveryAttempt 本次是第几次恢复，从 1 开始；达到 2 次后轮次以 {@code stop_reason=error} 结束
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
        if (recoveryAttempt < 1) {
            throw new IllegalArgumentException("recoveryAttempt 必须从 1 开始：" + recoveryAttempt);
        }
    }

    /** 恢复方式。 */
    public enum Mode {
        /** {@code content_epoch} 与失败的 epoch 不相等：进程被强杀，本轮内容没有写入，重放完整输入。 */
        REPLAY_INPUT,
        /** 相等：出错、中断、停机时已保存中途状态，不重放输入，输入为 {@code RecoveryOnly}。 */
        RESUME_SAVED_STATE
    }
}
