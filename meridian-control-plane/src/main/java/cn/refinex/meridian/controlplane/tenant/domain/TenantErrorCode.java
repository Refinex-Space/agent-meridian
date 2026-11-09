package cn.refinex.meridian.controlplane.tenant.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 租户的错误码。 */
public enum TenantErrorCode implements ErrorCode {
    /** 租户不存在或已删除。 */
    NOT_FOUND("tenant.not_found", 404, "租户不存在");

    private final String code;
    private final int status;
    private final String title;

    TenantErrorCode(String code, int status, String title) {
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
