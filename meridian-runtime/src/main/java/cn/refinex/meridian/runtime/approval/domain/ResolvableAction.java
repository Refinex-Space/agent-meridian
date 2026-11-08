package cn.refinex.meridian.runtime.approval.domain;

import cn.refinex.meridian.engine.spi.engine.RecoveryOrigin;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import java.util.Objects;

/**
 * 生成恢复轮时读出的一个待处理动作：动作本身、它的结论与是否已交给智能体，以及 {@code recovery_confirmation} 附带的恢复信息
 * （架构设计「待处理动作」「崩溃恢复」）。
 *
 * @param action 动作；必填
 * @param conclusion 结论；还在等待回应或随会话终止作废时为空
 * @param consumedTurnId 把结论交给智能体的恢复轮；为空表示尚未交出
 * @param idempotencyKey {@code recovery_confirmation}：账本中记录的原幂等键；其他类型为空
 * @param recoveryNote {@code recovery_confirmation}：恢复认领时生成的完整恢复说明（已成功与状态未知的调用）；其他类型为空
 * @param recoveryMode {@code recovery_confirmation}：恢复认领时判定的恢复方式；其他类型为空
 */
public record ResolvableAction(
        StoredPendingAction action,
        ActionConclusion conclusion,
        String consumedTurnId,
        String idempotencyKey,
        RecoveryNote recoveryNote,
        RecoveryOrigin.Mode recoveryMode) {

    /**
     * 创建动作。
     *
     * @throws NullPointerException 缺少动作时
     */
    public ResolvableAction {
        Objects.requireNonNull(action, "action");
    }

    /**
     * 是否已有结论。
     *
     * @return 有结论时为 true
     */
    public boolean concluded() {
        return conclusion != null;
    }

    /**
     * 结论是否已交给智能体。
     *
     * @return 已有恢复轮消费它时为 true
     */
    public boolean consumed() {
        return consumedTurnId != null;
    }
}
