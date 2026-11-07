package cn.refinex.meridian.runtime.delivery.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 发送事件时"事件与会话状态的组合不合法"的错误码（架构设计「消息投递」「待处理动作」），码值只增不改。 */
public enum EventErrorCode implements ErrorCode {
    /** 会话中没有回应事件的 {@code action_id} 指定的待处理动作。 */
    ACTION_NOT_FOUND("event.action_not_found", 409, "待处理动作不存在"),
    /** 回应事件的类型与动作类型不对应，例如用 {@code user.custom_tool_result} 回应 {@code tool_confirmation}。 */
    ACTION_TYPE_MISMATCH("event.action_type_mismatch", 409, "事件类型与待处理动作不对应"),
    /** 动作已被回应或已超时。 */
    ACTION_NOT_PENDING("event.action_not_pending", 409, "待处理动作已回应或已超时");

    private final String code;
    private final int status;
    private final String title;

    EventErrorCode(String code, int status, String title) {
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
