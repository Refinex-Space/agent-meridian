package cn.refinex.meridian.engine.spi.event;

import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * agent 域的事件：智能体的输出、工具调用与结果、上下文压缩，见架构设计「事件」「事件映射」。
 *
 * <p>AgentScope 的结束类事件只带 ID 不带内容，内核按 {@code blockId}、{@code toolUseId} 拼装增量，在结束时
 * 输出这里的完整事件；增量本身以 {@link StreamEvent} 出现在流上，不持久化。
 */
public sealed interface AgentOutputEvent extends EngineEvent {

    /**
     * {@code agent.message}：一个完整的文本块或数据块。
     *
     * @param blockId 块 ID，与流上的 {@code event_start}、{@code event_delta} 对应；必填
     * @param content 内容块，文本块拼装后的完整文本，或数据块；可为空列表
     */
    record Message(String blockId, List<MessageContent> content) implements AgentOutputEvent {

        /**
         * 创建智能体消息事件。
         *
         * @throws NullPointerException 缺少块 ID 时
         */
        public Message {
            Objects.requireNonNull(blockId, "blockId");
            content = content == null ? List.of() : List.copyOf(content);
        }
    }

    /**
     * {@code agent.thinking}：一个完整的思考块。
     *
     * @param blockId 块 ID；必填
     * @param text 思考内容；可为空，表示智能体配置不暴露思考内容（{@code expose_thinking=false}）
     */
    record Thinking(String blockId, String text) implements AgentOutputEvent {

        /**
         * 创建思考事件。
         *
         * @throws NullPointerException 缺少块 ID 时
         */
        public Thinking {
            Objects.requireNonNull(blockId, "blockId");
        }
    }

    /**
     * {@code agent.tool_use}：调用一个平台工具，入参为拼装后的完整入参。
     *
     * @param toolUseId 工具调用 ID；必填
     * @param name 工具名；必填
     * @param input 入参；必填，可为空映射
     */
    record ToolUse(String toolUseId, String name, Map<String, Object> input) implements AgentOutputEvent {

        /**
         * 创建工具调用事件。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public ToolUse {
            Objects.requireNonNull(toolUseId, "toolUseId");
            Objects.requireNonNull(name, "name");
            input = copyInput(input);
        }
    }

    /**
     * {@code agent.tool_result}：一个平台工具的结果。
     *
     * @param toolUseId 工具调用 ID；必填
     * @param name 工具名；必填
     * @param state 结果状态，取自 AgentScope {@code ToolResultState}；必填
     * @param permission 权限结果，由平台推导；必填
     * @param content 结果内容；超过 16 KB 时为空列表，内容存对象存储
     * @param contentRef 存入对象存储的结果引用；可为空，表示内容在 {@code content} 中
     */
    record ToolResult(
            String toolUseId,
            String name,
            ToolResultState state,
            PermissionOutcome permission,
            List<MessageContent> content,
            BlobRef contentRef)
            implements AgentOutputEvent {

        /**
         * 创建工具结果事件。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public ToolResult {
            Objects.requireNonNull(toolUseId, "toolUseId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(permission, "permission");
            content = content == null ? List.of() : List.copyOf(content);
        }
    }

    /**
     * {@code agent.mcp_tool_use}：调用一个 MCP 工具。
     *
     * @param toolUseId 工具调用 ID；必填
     * @param name 工具全名 {@code mcp__{server}__{tool}}；必填
     * @param serverName MCP 服务名；必填
     * @param input 入参；必填，可为空映射
     */
    record McpToolUse(String toolUseId, String name, String serverName, Map<String, Object> input)
            implements AgentOutputEvent {

        /**
         * 创建 MCP 工具调用事件。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public McpToolUse {
            Objects.requireNonNull(toolUseId, "toolUseId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(serverName, "serverName");
            input = copyInput(input);
        }
    }

    /**
     * {@code agent.mcp_tool_result}：一个 MCP 工具的结果，字段含义同 {@link ToolResult}。
     *
     * @param toolUseId 工具调用 ID；必填
     * @param name 工具全名；必填
     * @param serverName MCP 服务名；必填
     * @param state 结果状态；必填
     * @param permission 权限结果；必填
     * @param content 结果内容；超过 16 KB 时为空列表
     * @param contentRef 存入对象存储的结果引用；可为空
     */
    record McpToolResult(
            String toolUseId,
            String name,
            String serverName,
            ToolResultState state,
            PermissionOutcome permission,
            List<MessageContent> content,
            BlobRef contentRef)
            implements AgentOutputEvent {

        /**
         * 创建 MCP 工具结果事件。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public McpToolResult {
            Objects.requireNonNull(toolUseId, "toolUseId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(serverName, "serverName");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(permission, "permission");
            content = content == null ? List.of() : List.copyOf(content);
        }
    }

    /**
     * {@code agent.custom_tool_use}：调用一个由业务侧执行的自定义工具，随后本轮产生 {@code custom_tool_result} 动作。
     *
     * @param toolUseId 工具调用 ID；必填
     * @param name 自定义工具名；必填
     * @param input 入参；必填，可为空映射
     */
    record CustomToolUse(String toolUseId, String name, Map<String, Object> input) implements AgentOutputEvent {

        /**
         * 创建自定义工具调用事件。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public CustomToolUse {
            Objects.requireNonNull(toolUseId, "toolUseId");
            Objects.requireNonNull(name, "name");
            input = copyInput(input);
        }
    }

    /**
     * {@code agent.context_compacted}：上下文压缩完成，由 {@code CompactionGuardMiddleware} 在发现新的压缩摘要时发出。
     *
     * @param messagesBefore 压缩前上下文中的消息数，不小于 0
     * @param messagesAfter 压缩后上下文中的消息数（含摘要消息），不小于 0
     */
    record ContextCompacted(int messagesBefore, int messagesAfter) implements AgentOutputEvent {}

    /** 工具结果状态，与 AgentScope {@code ToolResultState} 一一对应。 */
    enum ToolResultState {
        /** 成功。 */
        SUCCESS("success"),
        /** 工具执行出错。 */
        ERROR("error"),
        /** 执行被中断。 */
        INTERRUPTED("interrupted"),
        /** 被权限拒绝，或审批被拒绝。 */
        DENIED("denied"),
        /** 已转入后台执行，结果稍后送达。 */
        RUNNING("running");

        private final String value;

        ToolResultState(String value) {
            this.value = value;
        }

        /**
         * 返回事件中使用的取值。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }
    }

    /** 工具调用的权限结果，见架构设计「事件映射」中 {@code TOOL_RESULT_END} 一行。 */
    enum PermissionOutcome {
        /** 规则放行，没有经过人工确认。 */
        ALLOWED("allowed"),
        /** 本轮有对应的确认记录，经人工批准。 */
        APPROVED("approved"),
        /** 结果状态为 DENIED。 */
        DENIED("denied");

        private final String value;

        PermissionOutcome(String value) {
            this.value = value;
        }

        /**
         * 返回事件中使用的取值。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }
    }

    private static Map<String, Object> copyInput(Map<String, Object> input) {
        // 入参来自模型输出的 JSON，可能含 null 值，Map.copyOf 会拒绝
        return input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
