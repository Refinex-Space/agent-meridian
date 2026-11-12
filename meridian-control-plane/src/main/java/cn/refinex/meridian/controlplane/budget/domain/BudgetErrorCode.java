package cn.refinex.meridian.controlplane.budget.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 预算的错误码（ADR-0048）。 */
public enum BudgetErrorCode implements ErrorCode {
    /** 预算不存在。 */
    NOT_FOUND("budget.not_found", 404, "预算不存在"),
    /** 同一级别、同一范围已有预算。 */
    EXISTS("budget.exists", 409, "该范围已有预算"),
    /** 预算的范围（API Key）不存在。 */
    SCOPE_NOT_FOUND("budget.scope_not_found", 404, "预算的范围不存在"),
    /** 范围不合法：租户级给了范围，项目或 API Key 不存在，会话 ID 格式不对，软阈值大于硬阈值。 */
    INVALID("budget.invalid", 400, "预算参数不合法");

    private final String code;
    private final int status;
    private final String title;

    BudgetErrorCode(String code, int status, String title) {
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
