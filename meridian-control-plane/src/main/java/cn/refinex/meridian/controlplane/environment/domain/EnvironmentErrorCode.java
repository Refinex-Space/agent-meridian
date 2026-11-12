package cn.refinex.meridian.controlplane.environment.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 环境的错误码（ADR-0047）。 */
public enum EnvironmentErrorCode implements ErrorCode {
    /** 环境不存在或已删除。 */
    NOT_FOUND("environment.not_found", 404, "环境不存在"),
    /** 环境的修订不存在。 */
    REVISION_NOT_FOUND("environment.revision_not_found", 404, "环境修订不存在"),
    /** 项目内已有同名的环境。 */
    NAME_TAKEN("environment.name_taken", 409, "名称已被使用");

    private final String code;
    private final int status;
    private final String title;

    EnvironmentErrorCode(String code, int status, String title) {
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
