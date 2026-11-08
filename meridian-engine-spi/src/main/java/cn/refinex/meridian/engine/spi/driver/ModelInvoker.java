package cn.refinex.meridian.engine.spi.driver;

import cn.refinex.meridian.engine.spi.event.TokenUsage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * OpenAI 兼容直通接口（{@code /v1/chat/completions}、{@code /v1/responses}）用的聊天调用，内部使用
 * {@code RoutedModel}，所以限流、预算与计量与智能体的模型调用一致，见架构设计「端口」「模型调用」。
 *
 * <p>租户、项目与预算句柄从 Reactor Context 中的 {@code PlatformContext} 取。
 */
public interface ModelInvoker {

    /**
     * 流式聊天调用。
     *
     * @param request 请求
     * @return 增量块；结束原因与用量在供应商给出它们的块中出现（通常是最后一两块，用量可能单独成块）；预算不足以
     *     {@code BudgetExceededException} 失败，限流等待超时以 {@code RateLimitTimeoutException} 失败，供应商错误以
     *     {@code ProviderUnavailableException} 或 {@code ProviderRejectedException} 失败
     */
    Flux<ChatChunk> stream(ChatRequest request);

    /**
     * 聊天请求。
     *
     * @param modelId 模型 ID（{@code mdl_…}）；必填
     * @param messages 消息，至少一条
     * @param tools 可调用的工具；可为空列表
     * @param maxTokens 最大输出 token 数，大于 0；可为空，表示使用模型目录的默认值
     * @param temperature 采样温度，取值 [0, 2]；可为空
     */
    record ChatRequest(
            String modelId,
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            Integer maxTokens,
            Double temperature) {

        /**
         * 创建请求。
         *
         * @throws NullPointerException 缺少模型 ID 时
         * @throws IllegalArgumentException 没有消息，或参数超出范围时
         */
        public ChatRequest {
            Objects.requireNonNull(modelId, "modelId");
            messages = messages == null ? List.of() : List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("聊天请求至少要有一条消息");
            }
            if (maxTokens != null && maxTokens <= 0) {
                throw new IllegalArgumentException("maxTokens 必须大于 0：" + maxTokens);
            }
            if (temperature != null && (temperature < 0 || temperature > 2)) {
                throw new IllegalArgumentException("temperature 必须在 [0, 2] 内：" + temperature);
            }
        }
    }

    /**
     * 一条聊天消息。
     *
     * @param role 角色；必填
     * @param content 文本内容；可为空，表示只有工具调用的助手消息
     * @param toolCalls 助手消息发起的工具调用；可为空列表
     * @param toolCallId 工具消息回应的调用 ID；{@link ChatRole#TOOL} 时必填，其余为空
     */
    record ChatMessage(ChatRole role, String content, List<ToolCall> toolCalls, String toolCallId) {

        /**
         * 创建消息。
         *
         * @throws NullPointerException 缺少角色时
         * @throws IllegalArgumentException 工具消息缺少调用 ID 时
         */
        public ChatMessage {
            Objects.requireNonNull(role, "role");
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if (role == ChatRole.TOOL && toolCallId == null) {
                throw new IllegalArgumentException("工具消息必须带 toolCallId");
            }
        }
    }

    /** 聊天消息的角色。 */
    enum ChatRole {
        /** 系统。 */
        SYSTEM,
        /** 用户。 */
        USER,
        /** 助手。 */
        ASSISTANT,
        /** 工具结果。 */
        TOOL
    }

    /**
     * 可调用的工具。
     *
     * @param name 工具名；必填
     * @param description 工具说明；可为空
     * @param parameters 入参的 JSON Schema；必填
     */
    record ToolDefinition(String name, String description, Map<String, Object> parameters) {

        /**
         * 创建工具定义。
         *
         * @throws NullPointerException 缺少工具名或入参 schema 时
         */
        public ToolDefinition {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(parameters, "parameters");
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }
    }

    /**
     * 助手消息中的一个完整工具调用。
     *
     * @param id 调用 ID；必填
     * @param name 工具名；必填
     * @param arguments 入参 JSON 文本；必填
     */
    record ToolCall(String id, String name, String arguments) {

        /**
         * 创建工具调用。
         *
         * @throws NullPointerException 缺少任一字段时
         */
        public ToolCall {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(arguments, "arguments");
        }
    }

    /**
     * 流式响应的一个增量块。
     *
     * @param textDelta 追加的文本；可为空
     * @param reasoningDelta 追加的思考内容；可为空
     * @param toolCalls 工具调用的增量；可为空列表
     * @param usage 用量，只在供应商报告用量的块中给出；其余为空
     * @param finishReason 结束原因，例如 {@code stop}、{@code tool_calls}、{@code length}，只在供应商给出它的块中出现；其余为空
     */
    record ChatChunk(
            String textDelta,
            String reasoningDelta,
            List<ToolCallDelta> toolCalls,
            TokenUsage usage,
            String finishReason) {

        /** 创建增量块，对工具调用列表做不可变复制。 */
        public ChatChunk {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    /**
     * 工具调用的增量，按 {@code index} 归并同一个调用。
     *
     * @param index 调用在本条助手消息中的序号，从 0 开始
     * @param id 调用 ID，首个增量给出；其余可为空
     * @param name 工具名，首个增量给出；其余可为空
     * @param argumentsDelta 追加的入参 JSON 片段；可为空
     */
    record ToolCallDelta(int index, String id, String name, String argumentsDelta) {

        /**
         * 创建工具调用增量。
         *
         * @throws IllegalArgumentException 序号为负时
         */
        public ToolCallDelta {
            if (index < 0) {
                throw new IllegalArgumentException("index 不能为负：" + index);
            }
        }
    }
}
