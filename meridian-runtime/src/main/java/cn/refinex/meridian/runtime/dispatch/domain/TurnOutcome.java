package cn.refinex.meridian.runtime.dispatch.domain;

/**
 * 执行结束时轮次进入的状态（架构设计「状态机」中的轮次状态）。{@code queued} 由置回队列处理，{@code cancelled} 只用于
 * 还没被认领的轮次，都不在这里。
 */
public enum TurnOutcome {
    /** 正常结束。 */
    COMPLETED("completed"),
    /** 被中断。 */
    INTERRUPTED("interrupted"),
    /** 出错结束。 */
    FAILED("failed"),
    /** 产生了待处理动作，等待调用方回应；线程仍被占用，回应后生成恢复轮。 */
    AWAITING_ACTION("awaiting_action");

    private final String value;

    TurnOutcome(String value) {
        this.value = value;
    }

    /**
     * 返回 {@code rt.turns.status} 中的取值。
     *
     * @return 例如 {@code awaiting_action}
     */
    public String value() {
        return value;
    }
}
