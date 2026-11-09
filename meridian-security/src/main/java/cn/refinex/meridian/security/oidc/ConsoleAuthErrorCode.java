package cn.refinex.meridian.security.oidc;

import cn.refinex.meridian.common.error.ErrorCode;

/** 控制台用户认证的错误码（架构设计「认证」中"控制台用户"一行）。 */
public enum ConsoleAuthErrorCode implements ErrorCode {
    /** 请求没有 {@code Authorization: Bearer}，或格式不对。 */
    UNAUTHENTICATED("auth.unauthenticated", 401, "未认证"),
    /** 令牌无效、已过期，或当前环境没有控制台身份来源。 */
    INVALID_CONSOLE_TOKEN("auth.invalid_console_token", 401, "控制台令牌无效");

    private final String code;
    private final int status;
    private final String title;

    ConsoleAuthErrorCode(String code, int status, String title) {
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
