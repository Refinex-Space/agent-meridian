package cn.refinex.meridian.engine.agentscope.events;

import io.agentscope.core.event.AgentEventType;

/**
 * 一个 {@link AgentEventType} 在映射中的处理方式，逐行对应架构设计「事件映射」表。
 *
 * <p>{@link #of(AgentEventType)} 是穷举的 switch 表达式：AgentScope 升级新增事件类型时编译失败，
 * {@code AgentEventDispositionTest} 另外遍历 {@code AgentEventType.values()} 断言 31 个值逐一对应表格。
 */
public enum AgentEventDisposition {
    /** 只在流上输出 {@code event_start} 或 {@code event_delta}，不持久化，并累积增量。 */
    STREAM_ONLY,
    /** 结束类事件：输出按增量拼装后的完整事件（{@code agent.message}、{@code agent.thinking}、{@code agent.tool_use}、{@code agent.tool_result} 等）。 */
    ASSEMBLED,
    /** 模型请求的起止：{@code span.model_request_start}、{@code span.model_request_end}。 */
    SPAN,
    /** 产生待处理动作：在终态 {@code session.status_requires_action} 中输出（自定义工具同时输出 {@code agent.custom_tool_use}）。 */
    PENDING_ACTION,
    /** 不单独输出，合并 {@code GenerateReason} 后在流结束时输出唯一的终态事件。 */
    TERMINAL,
    /** 不输出：平台已有对应事件（{@code session.status_running}、{@code user.*}），或信息已在其他事件中。 */
    IGNORED,
    /** 不输出，只记日志；子线程事件由 {@code SubagentRunner} 用自己的映射器经 {@code EventSinkPort} 写入（T13）。 */
    LOGGED,
    /**
     * 平台中间件以约定名字发出的自定义事件按名字映射为平台事件（{@code meridian.context_compacted} →
     * {@code agent.context_compacted}），其余只记日志（ADR-0032）。
     */
    PLATFORM_CUSTOM;

    /**
     * 返回事件类型的处理方式。
     *
     * @param type AgentScope 事件类型
     * @return 处理方式
     */
    public static AgentEventDisposition of(AgentEventType type) {
        return switch (type) {
            case AGENT_START, ALL_TOOLS_DENIED, USER_CONFIRM_RESULT, EXTERNAL_EXECUTION_RESULT -> IGNORED;
            case MODEL_CALL_START, MODEL_CALL_END -> SPAN;
            case TEXT_BLOCK_START,
                    TEXT_BLOCK_DELTA,
                    THINKING_BLOCK_START,
                    THINKING_BLOCK_DELTA,
                    DATA_BLOCK_START,
                    DATA_BLOCK_DELTA,
                    TOOL_CALL_START,
                    TOOL_CALL_DELTA,
                    TOOL_RESULT_START,
                    TOOL_RESULT_TEXT_DELTA,
                    TOOL_RESULT_DATA_DELTA -> STREAM_ONLY;
            case TEXT_BLOCK_END, THINKING_BLOCK_END, DATA_BLOCK_END, TOOL_CALL_END, TOOL_RESULT_END -> ASSEMBLED;
            case REQUIRE_USER_CONFIRM, REQUIRE_EXTERNAL_EXECUTION -> PENDING_ACTION;
            case EXCEED_MAX_ITERS, REQUEST_STOP, AGENT_RESULT, AGENT_END -> TERMINAL;
            case SUBAGENT_EXPOSED, HINT_BLOCK -> LOGGED;
            case CUSTOM -> PLATFORM_CUSTOM;
        };
    }
}
