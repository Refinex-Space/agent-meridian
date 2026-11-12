package cn.refinex.meridian.controlplane.webhook.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** Webhook 的错误码（ADR-0049）。 */
public enum WebhookErrorCode implements ErrorCode {
    /** Webhook 不存在或不属于该项目。 */
    NOT_FOUND("webhook.not_found", 404, "Webhook 不存在");

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
