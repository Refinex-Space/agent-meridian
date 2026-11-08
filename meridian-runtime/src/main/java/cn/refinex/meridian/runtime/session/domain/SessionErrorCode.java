package cn.refinex.meridian.runtime.session.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 会话与线程的错误码（架构设计「API 设计」「状态机」），码值只增不改。 */
public enum SessionErrorCode implements ErrorCode {
    /** 会话不存在，或不属于当前项目。 */
    SESSION_NOT_FOUND("session.not_found", 404, "会话不存在"),
    /** 会话已归档或删除，不再接受写入。 */
    SESSION_TERMINATED("session.terminated", 409, "会话已终止"),
    /** 会话正在执行（running 或 rescheduled），不能归档；需要停止时先中断或直接删除。 */
    SESSION_BUSY("session.busy", 409, "会话正在执行"),
    /** 线程不存在，或不属于该会话。 */
    THREAD_NOT_FOUND("thread.not_found", 404, "线程不存在"),
    /** 智能体或智能体版本不存在，或不属于当前项目。 */
    AGENT_NOT_FOUND("agent.not_found", 404, "智能体不存在"),
    /** 环境不存在，或不属于当前项目。 */
    ENVIRONMENT_NOT_FOUND("environment.not_found", 404, "环境不存在"),
    /** 智能体版本引用的技能版本不存在（配置错误，装配智能体时出现）。 */
    SKILL_NOT_FOUND("skill.not_found", 404, "技能不存在");

    private final String code;
    private final int status;
    private final String title;

    SessionErrorCode(String code, int status, String title) {
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
