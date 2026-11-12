package cn.refinex.meridian.runtime.webhook.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** Webhook 投递记录的错误码（ADR-0049），码值只增不改。 */
public enum WebhookErrorCode implements ErrorCode {
    /** 投递记录不存在、已过保留期，或不属于当前项目。 */
    DELIVERY_NOT_FOUND("webhook_delivery.not_found", 404, "投递记录不存在");

    private final String code;
    private final int status;
    private final String title;

    WebhookErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
