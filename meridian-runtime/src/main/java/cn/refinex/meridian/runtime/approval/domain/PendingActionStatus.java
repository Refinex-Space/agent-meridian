package cn.refinex.meridian.runtime.approval.domain;

/** 待处理动作的状态，即 {@code pending_actions.status}（架构设计「待处理动作」）。 */
public enum PendingActionStatus {
    /** 等待调用方回应。 */
    PENDING("pending"),
    /** 已回应。 */
    RESOLVED("resolved"),
    /** 已超时，或随会话终止作废。 */
    EXPIRED("expired");

    private final String value;

    PendingActionStatus(String value) {
        this.value = value;
    }

    /**
     * 返回数据库与 API 中的取值。
     *
     * @return 小写取值
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
    public static PendingActionStatus of(String value) {
        for (PendingActionStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的待处理动作状态：" + value);
    }
}
