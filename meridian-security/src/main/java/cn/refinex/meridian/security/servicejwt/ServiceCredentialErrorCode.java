package cn.refinex.meridian.security.servicejwt;

import cn.refinex.meridian.common.error.ErrorCode;

/** 服务凭据认证的错误码；缺少凭据与公开 API 一样是 {@code auth.unauthenticated}。 */
public enum ServiceCredentialErrorCode implements ErrorCode {
    /** 请求没有 {@code Authorization: Bearer}，或格式不对。 */
    UNAUTHENTICATED("auth.unauthenticated", 401, "未认证"),
    /** 服务凭据无效、已过期，或当前环境不接受服务凭据。 */
    INVALID_SERVICE_CREDENTIAL("auth.invalid_service_credential", 401, "服务凭据无效");

    private final String code;
    private final int status;
    private final String title;

    ServiceCredentialErrorCode(String code, int status, String title) {
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
