package cn.refinex.meridian.runtime.session.domain;

import cn.refinex.meridian.engine.spi.engine.RecoveryOrigin;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import java.util.List;
import java.util.Objects;

/**
 * 恢复判定的结论，见 {@link RecoveryPlanner}。三种结论都可能要求先重新投递被失败那次执行消费的 steer 消息。
 */
public sealed interface RecoveryDecision {

    /**
     * 是否先重新投递 {@code consumed_epoch} 等于失败 epoch 的 steer 消息：失败那次执行的内容没有保存时，它消费的 steer 不在状态里。
     *
     * @return 需要重新投递时为 true
     */
    boolean redeliverSteer();

    /**
     * 失败那次执行的 epoch。
     *
     * @return epoch
     */
    long failedEpoch();

    /**
     * 恢复次数已用尽（{@code recovery_attempt} 达到上限），本轮不再执行，以 {@code stop_reason=error} 结束。
     *
     * @param failedEpoch 失败那次执行的 epoch
     * @param recoveryAttempt 轮次的恢复次数
     * @param redeliverSteer 是否重新投递 steer
     */
    record Exhausted(long failedEpoch, int recoveryAttempt, boolean redeliverSteer) implements RecoveryDecision {}

    /**
     * 有状态未知的 {@code non_idempotent} 调用：本轮不执行，为每个调用创建 {@code recovery_confirmation} 待处理动作，
     * 会话转为 {@code requires_action}，由人决定是否用原幂等键重试。
     *
     * @param origin 恢复来源，回应后生成恢复轮时使用
     * @param redeliverSteer 是否重新投递 steer
     * @param unknownCalls 需要确认的调用，至少一个
     * @param note 此刻的完整恢复说明（已成功与状态未知的调用），随动作保存，回应后生成恢复轮时在它上面标出重试或跳过
     */
    record AwaitConfirmation(
            RecoveryOrigin origin, boolean redeliverSteer, List<ToolCallRecord> unknownCalls, RecoveryNote note)
            implements RecoveryDecision {

        /**
         * 创建结论。
         *
         * @throws IllegalArgumentException 没有需要确认的调用时
         */
        public AwaitConfirmation {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(note, "note");
            unknownCalls = List.copyOf(unknownCalls);
            if (unknownCalls.isEmpty()) {
                throw new IllegalArgumentException("至少要有一个需要确认的调用");
            }
        }

        @Override
        public long failedEpoch() {
            return origin.failedEpoch();
        }
    }

    /**
     * 继续执行本轮。
     *
     * @param origin 恢复来源
     * @param redeliverSteer 是否重新投递 steer
     * @param input 本次执行的输入：重放的完整输入（附恢复说明），或 {@code RecoveryOnly}
     */
    record Resume(RecoveryOrigin origin, boolean redeliverSteer, TurnInput input) implements RecoveryDecision {

        /**
         * 创建结论。
         *
         * @throws NullPointerException 缺少来源或输入时
         */
        public Resume {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(input, "input");
        }

        @Override
        public long failedEpoch() {
            return origin.failedEpoch();
        }

        /**
         * 本次执行附带的恢复说明。
         *
         * @return 恢复说明
         */
        public RecoveryNote note() {
            return input.recovery();
        }
    }
}
