package cn.refinex.meridian.engine.agentscope.events;

import cn.refinex.meridian.engine.spi.event.StopReason;
import io.agentscope.core.message.GenerateReason;
import java.util.Objects;

/**
 * {@link GenerateReason} 到本轮终态的映射，逐行对应架构设计「状态机」中的停止原因表；异常的映射见
 * {@link TurnEventMapper#fail(Throwable)}。
 *
 * <p>{@link #of(GenerateReason)} 是穷举的 switch 表达式，AgentScope 升级新增取值时编译失败；
 * {@code StopReasonMappingTest} 遍历 {@code GenerateReason.values()} 断言每个值的映射。
 */
public final class StopReasonMapping {

    private StopReasonMapping() {}

    /**
     * 返回结束原因对应的终态。
     *
     * @param reason AgentScope 结束原因；为空表示调用没有给出结束原因（未知值）
     * @return 终态
     */
    public static Outcome of(GenerateReason reason) {
        if (reason == null) {
            return new Outcome(Kind.UNEXPECTED, StopReason.ERROR);
        }
        return switch (reason) {
            // 2.0.4 从不赋值 STRUCTURED_OUTPUT，结构化输出完成时为 MODEL_STOP；两者都是 end_turn
            case MODEL_STOP, STRUCTURED_OUTPUT, TOOL_RETURN_DIRECT -> new Outcome(Kind.IDLE, StopReason.END_TURN);
            case PERMISSION_ASKING, TOOL_SUSPENDED -> new Outcome(Kind.REQUIRES_ACTION, null);
            case INTERRUPTED -> new Outcome(Kind.IDLE, StopReason.INTERRUPTED);
            case MAX_ITERATIONS -> new Outcome(Kind.IDLE, StopReason.MAX_TURNS);
            case ALL_TOOLS_DENIED -> new Outcome(Kind.IDLE, StopReason.TOOLS_DENIED);
            case REASONING_STOP_REQUESTED, ACTING_STOP_REQUESTED, MIDDLEWARE_STOP_REQUESTED ->
                new Outcome(Kind.STOP_REQUESTED, StopReason.END_TURN);
            // 一次调用不应以"还有工具调用"结束，出现即是内核行为异常
            case TOOL_CALLS -> new Outcome(Kind.UNEXPECTED, StopReason.ERROR);
        };
    }

    /** 终态的类别。 */
    public enum Kind {
        /** {@code session.status_idle}，停止原因见 {@link Outcome#stopReason()}。 */
        IDLE,
        /** 不进入 idle，以 {@code session.status_requires_action} 结束，动作来自本轮的待确认或挂起的调用。 */
        REQUIRES_ACTION,
        /**
         * 中间件或钩子请求停止：平台中间件发起时取它经 {@code PlatformStopRequest} 记录的原因，
         * 否则为 {@link Outcome#stopReason()}（{@code end_turn}）。
         */
        STOP_REQUESTED,
        /** 不应出现的结束原因（{@code TOOL_CALLS} 或缺失）：{@code stop_reason=error}，同时记录告警。 */
        UNEXPECTED
    }

    /**
     * 映射结果。
     *
     * @param kind 终态类别；必填
     * @param stopReason 停止原因；{@link Kind#REQUIRES_ACTION} 时为空，{@link Kind#STOP_REQUESTED} 时是没有平台记录时的默认值
     */
    public record Outcome(Kind kind, StopReason stopReason) {

        /**
         * 创建映射结果。
         *
         * @throws NullPointerException 缺少类别时
         */
        public Outcome {
            Objects.requireNonNull(kind, "kind");
        }
    }
}
