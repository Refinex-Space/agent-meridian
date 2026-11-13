package cn.refinex.meridian.controlplane.member.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 成员与角色绑定的错误码。 */
public enum MemberErrorCode implements ErrorCode {
    /** 成员不存在。 */
    NOT_FOUND("member.not_found", 404, "成员不存在"),
    /** 该主体已是租户成员。 */
    ALREADY_EXISTS("member.already_exists", 409, "成员已存在"),
    /** 添加成员时既没有主体标识也没有邮箱。 */
    IDENTITY_REQUIRED("member.identity_required", 400, "需要用户标识或邮箱"),
    /** 移除或撤销后租户将没有所有者。 */
    LAST_OWNER("member.last_owner", 409, "不能移除最后一位所有者"),
    /** 角色绑定不存在。 */
    ROLE_BINDING_NOT_FOUND("role_binding.not_found", 404, "角色绑定不存在"),
    /** 同一成员同一范围的同一角色已存在。 */
    ROLE_BINDING_ALREADY_EXISTS("role_binding.already_exists", 409, "角色已授予");

    private final String code;
    private final int status;
    private final String title;

    MemberErrorCode(String code, int status, String title) {
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
