package cn.refinex.meridian.runtime.modelrouting.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 模型路由的错误码（架构设计「模型调用」），码值只增不改。预算与限流的错误码在 engine-spi 的 {@code ModelErrorCode}。 */
public enum ModelRoutingErrorCode implements ErrorCode {
    /** 模型不在模型目录中，或未授权给当前项目。 */
    MODEL_NOT_FOUND("model.not_found", 404, "模型不存在"),
    /** 供应商凭据不存在或不可读。 */
    CREDENTIAL_UNAVAILABLE("model.credential_unavailable", 503, "模型供应商的凭据不可用");

    private final String code;
    private final int status;
    private final String title;

    ModelRoutingErrorCode(String code, int status, String title) {
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
