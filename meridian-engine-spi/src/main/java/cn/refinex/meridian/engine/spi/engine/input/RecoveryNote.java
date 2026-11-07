package cn.refinex.meridian.engine.spi.engine.input;

import java.util.List;
import java.util.Objects;

/**
 * 恢复说明：上一次执行中断后，哪些工具调用已经成功、哪些状态未知、哪些按恢复确认重试或跳过，
 * 见架构设计「崩溃恢复」「待处理动作」。
 *
 * <p>它由平台根据 {@code tool_invocations} 生成，由 {@code RecoveryNoteMiddleware} 在恢复轮的第一次推理前
 * 追加到输入和 {@code AgentState} 上下文，不走调用入参：AgentScope 在停机中断后的下一次调用会丢弃全部入参。
 *
 * @param toolCalls 上一次执行中涉及的工具调用，按发起顺序；可为空列表，表示中断时没有进行中的工具调用
 */
public record RecoveryNote(List<RecoveredToolCall> toolCalls) {

    /** 创建恢复说明，对列表做不可变复制。 */
    public RecoveryNote {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /**
     * 一个工具调用在恢复时的状态。
     *
     * @param toolUseId 工具调用 ID；必填
     * @param toolName 工具名；必填
     * @param outcome 恢复时的状态；必填
     * @param resultSummary 结果摘要，{@link Outcome#SUCCEEDED} 与 {@link Outcome#RETRIED} 时给出；其余可为空
     */
    public record RecoveredToolCall(String toolUseId, String toolName, Outcome outcome, String resultSummary) {

        /**
         * 创建工具调用的恢复状态。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public RecoveredToolCall {
            Objects.requireNonNull(toolUseId, "toolUseId");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** 工具调用在恢复时的状态。 */
    public enum Outcome {
        /** 已经成功执行，不再重复执行。 */
        SUCCEEDED,
        /** 状态未知：可以重放的调用会被智能体重新发起；{@code non_idempotent} 调用要先经恢复确认。 */
        UNKNOWN,
        /** 恢复确认选择 {@code retry}，平台已用原幂等键重新执行。 */
        RETRIED,
        /** 恢复确认选择 {@code skip}（或超时），没有重新执行。 */
        SKIPPED
    }
}
