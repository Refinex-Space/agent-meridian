package cn.refinex.meridian.controlplane.project.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 项目的错误码。 */
public enum ProjectErrorCode implements ErrorCode {
    /** 项目不存在、已删除，或不属于路径中的租户。 */
    NOT_FOUND("project.not_found", 404, "项目不存在");

    private final String code;
    private final int status;
    private final String title;

    ProjectErrorCode(String code, int status, String title) {
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
