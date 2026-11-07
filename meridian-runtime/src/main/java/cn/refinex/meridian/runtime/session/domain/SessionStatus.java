package cn.refinex.meridian.runtime.session.domain;

/**
 * 会话的对外状态（架构设计「状态机」），线程状态使用同一组取值。
 *
 * <p>状态只随 {@code session.status_*} 与 {@code session.thread_status_*} 事件推进，并与事件写入在同一事务中完成，
 * 所以 {@code rt.sessions.status} 与事件日志不会分叉。
 */
public enum SessionStatus {
    /** 空闲：没有执行中的轮次。 */
    IDLE("idle"),
    /** 执行中。 */
    RUNNING("running"),
    /** 等待调用方回应待处理动作。 */
    REQUIRES_ACTION("requires_action"),
    /** 轮次被置回队列，等待其他 Worker 认领。 */
    RESCHEDULED("rescheduled"),
    /** 已归档或删除。 */
    TERMINATED("terminated");

    private final String value;

    SessionStatus(String value) {
        this.value = value;
    }

    /**
     * 返回数据库与 API 中的取值。
     *
     * @return 例如 {@code requires_action}
     */
    public String value() {
        return value;
    }

    /**
     * 按取值查找状态。
     *
     * @param value 数据库中的取值
     * @return 状态
     * @throws IllegalArgumentException 取值未知时
     */
    public static SessionStatus of(String value) {
        for (SessionStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的会话状态：" + value);
    }
}
