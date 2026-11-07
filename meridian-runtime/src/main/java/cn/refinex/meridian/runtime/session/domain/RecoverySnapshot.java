package cn.refinex.meridian.runtime.session.domain;

import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 恢复认领时的快照：恢复判定的全部输入（架构设计「崩溃恢复」，ADR-0028）。
 *
 * @param input 本轮在 {@code turn_inputs} 中的完整输入；必填
 * @param epoch 本次执行的 epoch，大于 1（等于 1 说明从未执行过，不是恢复）
 * @param recoveryAttempt 轮次的 {@code recovery_attempt}
 * @param turnContentEpoch 本轮在线程状态中最近一次内容写入的 epoch；本轮从未写入内容时为空
 * @param calls 本轮此前各次执行的工具调用，按开始时间排列
 */
public record RecoverySnapshot(
        TurnInput input, long epoch, int recoveryAttempt, OptionalLong turnContentEpoch, List<ToolCallRecord> calls) {

    /**
     * 创建快照。
     *
     * @throws NullPointerException 缺少输入或内容 epoch 时
     * @throws IllegalArgumentException epoch 不大于 1 时
     */
    public RecoverySnapshot {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(turnContentEpoch, "turnContentEpoch");
        if (epoch < 2) {
            throw new IllegalArgumentException("恢复认领的 epoch 必须大于 1：" + epoch);
        }
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    /**
     * 失败那次执行的 epoch。认领时 epoch 加一，所以是本次 epoch 减一（ADR-0027 第 6 条）。
     *
     * @return epoch
     */
    public long failedEpoch() {
        return epoch - 1;
    }
}
