package cn.refinex.meridian.controlplane.policy.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 策略规则的错误码（ADR-0047）。 */
public enum PolicyErrorCode implements ErrorCode {
    /** 规则不存在。 */
    NOT_FOUND("permission_rule.not_found", 404, "权限规则不存在"),
    /** 同一层中已有相同的规则（工具、内容、结果）。 */
    ALREADY_EXISTS("permission_rule.already_exists", 409, "相同的规则已存在");

    private final String code;
    private final int status;
    private final String title;

    PolicyErrorCode(String code, int status, String title) {
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
