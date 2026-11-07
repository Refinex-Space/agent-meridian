package cn.refinex.meridian.engine.spi.event;

/**
 * 轮次的停止原因，即 {@code session.status_idle} 事件的 {@code stop_reason}，见架构设计「状态机」。
 *
 * <p>从 AgentScope {@code GenerateReason} 与异常到这里的映射必须穷举，由 T04 的单元测试保证；
 * 以 {@code PERMISSION_ASKING}、{@code TOOL_SUSPENDED} 结束的轮次不进入 idle，没有停止原因。
 */
public enum StopReason {
    /** 正常结束：模型停止、结构化输出完成、工具结果直接返回，或非平台中间件请求停止。 */
    END_TURN("end_turn"),
    /** 被用户中断。 */
    INTERRUPTED("interrupted"),
    /** 达到单轮最大推理次数（{@code maxIters}）。 */
    MAX_TURNS("max_turns"),
    /** 预算耗尽（{@code BudgetExceededException}）。 */
    BUDGET_EXCEEDED("budget_exceeded"),
    /** 本次推理发起的工具调用全部被拒绝。 */
    TOOLS_DENIED("tools_denied"),
    /** 不可重试的错误、未知的结束原因，或恢复次数用尽；同时告警。 */
    ERROR("error");

    private final String value;

    StopReason(String value) {
        this.value = value;
    }

    /**
     * 返回事件与 API 中使用的取值。
     *
     * @return 小写下划线形式的取值
     */
    public String value() {
        return value;
    }
}
