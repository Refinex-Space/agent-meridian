package cn.refinex.meridian.engine.spi.event;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 事件类型清单，与架构设计「事件」一节的"事件类型清单"表逐项对应，每种类型登记它在 {@link EngineEvent}
 * 层级中的记录类。
 *
 * <p>新增事件类型时先改架构设计的清单，再在这里与对应的域接口中各加一项；单元测试会比对文档、本枚举与
 * 封闭层级三者，漏掉任何一处都会失败。
 */
public enum EventType {
    /** 用户消息。 */
    USER_MESSAGE("user.message", Domain.USER, UserEvent.Message.class),
    /** 用户中断。 */
    USER_INTERRUPT("user.interrupt", Domain.USER, UserEvent.Interrupt.class),
    /** 工具确认的回应。 */
    USER_TOOL_CONFIRMATION("user.tool_confirmation", Domain.USER, UserEvent.ToolConfirmation.class),
    /** 自定义工具结果的回传。 */
    USER_CUSTOM_TOOL_RESULT("user.custom_tool_result", Domain.USER, UserEvent.CustomToolResult.class),
    /** 恢复确认的回应。 */
    USER_RECOVERY_CONFIRMATION("user.recovery_confirmation", Domain.USER, UserEvent.RecoveryConfirmation.class),

    /** 智能体输出的完整文本块或数据块。 */
    AGENT_MESSAGE("agent.message", Domain.AGENT, AgentOutputEvent.Message.class),
    /** 智能体的思考块。 */
    AGENT_THINKING("agent.thinking", Domain.AGENT, AgentOutputEvent.Thinking.class),
    /** 平台工具调用。 */
    AGENT_TOOL_USE("agent.tool_use", Domain.AGENT, AgentOutputEvent.ToolUse.class),
    /** 平台工具结果。 */
    AGENT_TOOL_RESULT("agent.tool_result", Domain.AGENT, AgentOutputEvent.ToolResult.class),
    /** MCP 工具调用。 */
    AGENT_MCP_TOOL_USE("agent.mcp_tool_use", Domain.AGENT, AgentOutputEvent.McpToolUse.class),
    /** MCP 工具结果。 */
    AGENT_MCP_TOOL_RESULT("agent.mcp_tool_result", Domain.AGENT, AgentOutputEvent.McpToolResult.class),
    /** 自定义工具调用。 */
    AGENT_CUSTOM_TOOL_USE("agent.custom_tool_use", Domain.AGENT, AgentOutputEvent.CustomToolUse.class),
    /** 上下文压缩完成。 */
    AGENT_CONTEXT_COMPACTED("agent.context_compacted", Domain.AGENT, AgentOutputEvent.ContextCompacted.class),

    /** 轮次开始。 */
    SESSION_STATUS_RUNNING("session.status_running", Domain.SESSION, SessionEvent.StatusRunning.class),
    /** 轮次结束，线程空闲。 */
    SESSION_STATUS_IDLE("session.status_idle", Domain.SESSION, SessionEvent.StatusIdle.class),
    /** 等待调用方回应待处理动作。 */
    SESSION_STATUS_REQUIRES_ACTION(
            "session.status_requires_action", Domain.SESSION, SessionEvent.StatusRequiresAction.class),
    /** 轮次被置回队列。 */
    SESSION_STATUS_RESCHEDULED("session.status_rescheduled", Domain.SESSION, SessionEvent.StatusRescheduled.class),
    /** 会话被归档或删除。 */
    SESSION_STATUS_TERMINATED("session.status_terminated", Domain.SESSION, SessionEvent.StatusTerminated.class),
    /** 本轮出错。 */
    SESSION_ERROR("session.error", Domain.SESSION, SessionEvent.Error.class),
    /** 模型调用用量。 */
    SESSION_USAGE("session.usage", Domain.SESSION, SessionEvent.Usage.class),
    /** 用户消息被消费。 */
    SESSION_MESSAGE_CONSUMED("session.message_consumed", Domain.SESSION, SessionEvent.MessageConsumed.class),
    /** 创建了子线程。 */
    SESSION_THREAD_CREATED("session.thread_created", Domain.SESSION, SessionEvent.ThreadCreated.class),
    /** 子线程开始执行。 */
    SESSION_THREAD_STATUS_RUNNING(
            "session.thread_status_running", Domain.SESSION, SessionEvent.ThreadStatusRunning.class),
    /** 子线程执行结束。 */
    SESSION_THREAD_STATUS_IDLE("session.thread_status_idle", Domain.SESSION, SessionEvent.ThreadStatusIdle.class),

    /** 模型请求开始。 */
    SPAN_MODEL_REQUEST_START("span.model_request_start", Domain.SPAN, SpanEvent.ModelRequestStart.class),
    /** 模型请求结束。 */
    SPAN_MODEL_REQUEST_END("span.model_request_end", Domain.SPAN, SpanEvent.ModelRequestEnd.class),

    /** 内容块开始，只在流上。 */
    EVENT_START("event_start", Domain.STREAM, StreamEvent.EventStart.class),
    /** 内容块增量，只在流上。 */
    EVENT_DELTA("event_delta", Domain.STREAM, StreamEvent.EventDelta.class),
    /** 心跳，只在流上。 */
    PING("ping", Domain.STREAM, StreamEvent.Ping.class);

    private static final Map<Class<?>, EventType> BY_CLASS =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(t -> t.eventClass, Function.identity()));

    private static final Map<String, EventType> BY_VALUE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(t -> t.value, Function.identity()));

    private final String value;
    private final Domain domain;
    private final Class<? extends EngineEvent> eventClass;

    EventType(String value, Domain domain, Class<? extends EngineEvent> eventClass) {
        this.value = value;
        this.domain = domain;
        this.eventClass = eventClass;
    }

    /**
     * 返回事件类型的取值，即 {@code session_events.type} 与 SSE 的 {@code event:} 字段。
     *
     * @return 例如 {@code agent.tool_result}
     */
    public String value() {
        return value;
    }

    /**
     * 返回事件所属的域。
     *
     * @return 域
     */
    public Domain domain() {
        return domain;
    }

    /**
     * 返回本类型在 {@link EngineEvent} 层级中的记录类。
     *
     * @return 记录类
     */
    public Class<? extends EngineEvent> eventClass() {
        return eventClass;
    }

    /**
     * 是否写入 {@code session_events}。只在流上出现的事件不持久化。
     *
     * @return 持久化时为 true
     */
    public boolean isPersisted() {
        return domain != Domain.STREAM;
    }

    /**
     * 按记录类查找事件类型。
     *
     * @param eventClass {@link EngineEvent} 的具体记录类
     * @return 事件类型
     * @throws IllegalArgumentException 该类没有登记时
     */
    public static EventType of(Class<?> eventClass) {
        EventType type = BY_CLASS.get(eventClass);
        if (type == null) {
            throw new IllegalArgumentException("没有登记的事件类：" + eventClass.getName());
        }
        return type;
    }

    /**
     * 按取值查找事件类型。
     *
     * @param value 事件类型的取值，例如 {@code session.status_idle}
     * @return 事件类型
     * @throws IllegalArgumentException 取值未知时
     */
    public static EventType fromValue(String value) {
        EventType type = BY_VALUE.get(value);
        if (type == null) {
            throw new IllegalArgumentException("未知的事件类型：" + value);
        }
        return type;
    }

    /** 事件类型的域，即类型取值中第一个点号之前的部分；只在流上的事件单独成一类。 */
    public enum Domain {
        /** 调用方发送的事件。 */
        USER,
        /** 智能体的输出。 */
        AGENT,
        /** 会话与线程的状态。 */
        SESSION,
        /** 模型请求的起止。 */
        SPAN,
        /** 只在流上出现，不持久化。 */
        STREAM
    }
}
