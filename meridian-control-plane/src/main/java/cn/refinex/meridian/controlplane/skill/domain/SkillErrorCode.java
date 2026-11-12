package cn.refinex.meridian.controlplane.skill.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 技能的错误码（ADR-0047）。 */
public enum SkillErrorCode implements ErrorCode {
    /** 技能不存在或已删除。 */
    NOT_FOUND("skill.not_found", 404, "技能不存在"),
    /** 技能版本不存在。 */
    VERSION_NOT_FOUND("skill.version_not_found", 404, "技能版本不存在"),
    /** 项目内已有同名的技能。 */
    NAME_TAKEN("skill.name_taken", 409, "名称已被使用"),
    /** 技能仍被已发布的智能体版本引用；problem+json 带 {@code references}。 */
    IN_USE("skill.in_use", 409, "技能仍被引用");

    private final String code;
    private final int status;
    private final String title;

    SkillErrorCode(String code, int status, String title) {
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
