package cn.refinex.meridian.security.apikey;

import cn.refinex.meridian.common.error.ErrorCode;

/** 认证与授权的错误码（架构设计「认证」「授权」）。 */
public enum ApiKeyErrorCode implements ErrorCode {
    /** 请求没有 {@code Authorization: Bearer}，或格式不对。 */
    UNAUTHENTICATED("auth.unauthenticated", 401, "未认证"),
    /** API Key 不存在、已吊销或已过期。 */
    INVALID_API_KEY("auth.invalid_api_key", 401, "API Key 无效"),
    /** API Key 缺少该操作所需的作用域。 */
    INSUFFICIENT_SCOPE("auth.insufficient_scope", 403, "API Key 缺少所需的作用域");

    private final String code;
    private final int status;
    private final String title;

    ApiKeyErrorCode(String code, int status, String title) {
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
