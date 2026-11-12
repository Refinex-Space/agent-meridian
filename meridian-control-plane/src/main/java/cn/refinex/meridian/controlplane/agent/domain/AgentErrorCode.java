package cn.refinex.meridian.controlplane.agent.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 智能体与版本的错误码（ADR-0047）。 */
public enum AgentErrorCode implements ErrorCode {
    /** 智能体不存在、已删除，或（按智能体解析、公开接口）没有发布过版本。 */
    NOT_FOUND("agent.not_found", 404, "智能体不存在"),
    /** 版本不存在；内部解析时草稿也算不存在。 */
    VERSION_NOT_FOUND("agent.version_not_found", 404, "智能体版本不存在"),
    /** 项目内已有同名的智能体。 */
    NAME_TAKEN("agent.name_taken", 409, "名称已被使用"),
    /** 已发布的版本不可修改、删除或再次发布。 */
    VERSION_PUBLISHED("agent.version_published", 409, "版本已发布");

    private final String code;
    private final int status;
    private final String title;

    AgentErrorCode(String code, int status, String title) {
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
