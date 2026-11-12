package cn.refinex.meridian.runtime.webhook.domain;

/** 投递状态，取值与规范的 {@code WebhookDeliveryStatus} 相同。 */
public enum DeliveryStatus {
    /** 等待投递或重试。 */
    PENDING("pending"),
    /** 接收方返回了 2xx。 */
    SUCCEEDED("succeeded"),
    /** 重试用尽、过了截止时间，或 Webhook 已删除、停用。 */
    FAILED("failed");

    private final String value;

    DeliveryStatus(String value) {
        this.value = value;
    }

    /**
     * 存储与对外的取值。
     *
     * @return 取值
     */
    public String value() {
        return value;
    }

    /**
     * 按取值查找。
     *
     * @param value 取值
     * @return 状态
     * @throws IllegalArgumentException 未知取值时
     */
    public static DeliveryStatus of(String value) {
        for (DeliveryStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的投递状态：" + value);
    }
}
