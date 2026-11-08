package cn.refinex.meridian.engine.agentscope.events;

import static cn.refinex.meridian.engine.agentscope.events.AgentEventDisposition.ASSEMBLED;
import static cn.refinex.meridian.engine.agentscope.events.AgentEventDisposition.IGNORED;
import static cn.refinex.meridian.engine.agentscope.events.AgentEventDisposition.LOGGED;
import static cn.refinex.meridian.engine.agentscope.events.AgentEventDisposition.PENDING_ACTION;
import static cn.refinex.meridian.engine.agentscope.events.AgentEventDisposition.PLATFORM_CUSTOM;
import static cn.refinex.meridian.engine.agentscope.events.AgentEventDisposition.SPAN;
import static cn.refinex.meridian.engine.agentscope.events.AgentEventDisposition.STREAM_ONLY;
import static cn.refinex.meridian.engine.agentscope.events.AgentEventDisposition.TERMINAL;
import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.event.AgentEventType;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 31 个 {@link AgentEventType} 都有明确的映射或"忽略"决定，与架构设计「事件映射」表逐行一致（T04-A02）。
 * AgentScope 升级新增事件类型时，本测试与 {@link AgentEventDisposition#of} 的穷举 switch 一起失败。
 */
class AgentEventDispositionTest {

    /** 架构设计「事件映射」表，逐行抄录。 */
    private static final Map<AgentEventType, AgentEventDisposition> TABLE = new EnumMap<>(Map.ofEntries(
            Map.entry(AgentEventType.AGENT_START, IGNORED),
            Map.entry(AgentEventType.MODEL_CALL_START, SPAN),
            Map.entry(AgentEventType.MODEL_CALL_END, SPAN),
            Map.entry(AgentEventType.TEXT_BLOCK_START, STREAM_ONLY),
            Map.entry(AgentEventType.TEXT_BLOCK_DELTA, STREAM_ONLY),
            Map.entry(AgentEventType.TEXT_BLOCK_END, ASSEMBLED),
            Map.entry(AgentEventType.THINKING_BLOCK_START, STREAM_ONLY),
            Map.entry(AgentEventType.THINKING_BLOCK_DELTA, STREAM_ONLY),
            Map.entry(AgentEventType.THINKING_BLOCK_END, ASSEMBLED),
            Map.entry(AgentEventType.DATA_BLOCK_START, STREAM_ONLY),
            Map.entry(AgentEventType.DATA_BLOCK_DELTA, STREAM_ONLY),
            Map.entry(AgentEventType.DATA_BLOCK_END, ASSEMBLED),
            Map.entry(AgentEventType.TOOL_CALL_START, STREAM_ONLY),
            Map.entry(AgentEventType.TOOL_CALL_DELTA, STREAM_ONLY),
            Map.entry(AgentEventType.TOOL_CALL_END, ASSEMBLED),
            Map.entry(AgentEventType.TOOL_RESULT_START, STREAM_ONLY),
            Map.entry(AgentEventType.TOOL_RESULT_TEXT_DELTA, STREAM_ONLY),
            Map.entry(AgentEventType.TOOL_RESULT_DATA_DELTA, STREAM_ONLY),
            Map.entry(AgentEventType.TOOL_RESULT_END, ASSEMBLED),
            Map.entry(AgentEventType.REQUIRE_USER_CONFIRM, PENDING_ACTION),
            Map.entry(AgentEventType.REQUIRE_EXTERNAL_EXECUTION, PENDING_ACTION),
            Map.entry(AgentEventType.EXCEED_MAX_ITERS, TERMINAL),
            Map.entry(AgentEventType.REQUEST_STOP, TERMINAL),
            Map.entry(AgentEventType.AGENT_RESULT, TERMINAL),
            Map.entry(AgentEventType.AGENT_END, TERMINAL),
            Map.entry(AgentEventType.ALL_TOOLS_DENIED, IGNORED),
            Map.entry(AgentEventType.USER_CONFIRM_RESULT, IGNORED),
            Map.entry(AgentEventType.EXTERNAL_EXECUTION_RESULT, IGNORED),
            Map.entry(AgentEventType.SUBAGENT_EXPOSED, LOGGED),
            Map.entry(AgentEventType.HINT_BLOCK, LOGGED),
            Map.entry(AgentEventType.CUSTOM, PLATFORM_CUSTOM)));

    @Test
    void agentScope204HasExactly31EventTypes() {
        assertThat(AgentEventType.values()).hasSize(31);
        assertThat(TABLE).as("表格覆盖全部取值").hasSize(AgentEventType.values().length);
    }

    @Test
    void everyEventTypeHasTheDispositionOfTheArchitectureTable() {
        for (AgentEventType type : AgentEventType.values()) {
            assertThat(AgentEventDisposition.of(type)).as(type.name()).isEqualTo(TABLE.get(type));
        }
    }
}
