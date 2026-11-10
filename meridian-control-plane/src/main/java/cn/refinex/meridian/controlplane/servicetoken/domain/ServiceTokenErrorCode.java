package cn.refinex.meridian.controlplane.servicetoken.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 令牌端点的错误码（ADR-0044）。 */
public enum ServiceTokenErrorCode implements ErrorCode {
    /** 缺少 HTTP Basic 认证，或服务名与客户端密钥不匹配。 */
    INVALID_SERVICE_CLIENT("auth.invalid_service_client", 401, "服务客户端认证失败");

    private final String code;
    private final int status;
    private final String title;

    ServiceTokenErrorCode(String code, int status, String title) {
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
