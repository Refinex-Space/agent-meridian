package cn.refinex.meridian.runtime.modelrouting.api;

import cn.refinex.meridian.api.model.ChatCompletionChoice;
import cn.refinex.meridian.api.model.ChatCompletionChunk;
import cn.refinex.meridian.api.model.ChatCompletionChunkChoice;
import cn.refinex.meridian.api.model.ChatCompletionChunkDelta;
import cn.refinex.meridian.api.model.ChatCompletionChunkFunction;
import cn.refinex.meridian.api.model.ChatCompletionChunkToolCall;
import cn.refinex.meridian.api.model.ChatCompletionFunctionCall;
import cn.refinex.meridian.api.model.ChatCompletionMessage;
import cn.refinex.meridian.api.model.ChatCompletionMessageToolCall;
import cn.refinex.meridian.api.model.ChatCompletionResponseMessage;
import cn.refinex.meridian.api.model.ChatCompletionTool;
import cn.refinex.meridian.api.model.CompletionTokensDetails;
import cn.refinex.meridian.api.model.CompletionUsage;
import cn.refinex.meridian.api.model.CreateChatCompletionRequest;
import cn.refinex.meridian.api.model.PromptTokensDetails;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatChunk;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatMessage;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatRequest;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatRole;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ToolCall;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ToolCallDelta;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ToolDefinition;
import cn.refinex.meridian.engine.spi.event.TokenUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OpenAI 兼容的对话补全与平台 {@code ModelInvoker} 之间的映射：请求翻译、增量块翻译、非流式的聚合。
 *
 * <p>无状态的静态方法；聚合与流式的首块标记由调用方持有的 {@link Aggregate}、{@link ChunkWriter} 维护。
 */
final class ChatCompletionMapper {

    /** 函数工具没有给出入参 schema 时使用的无参数对象。 */
    private static final Map<String, Object> NO_PARAMETERS = Map.of("type", "object", "properties", Map.of());

    private ChatCompletionMapper() {}

    /**
     * 请求翻译为平台的聊天请求。
     *
     * @param request 请求 DTO（已按规范校验）
     * @return 聊天请求
     * @throws ValidationException 工具消息缺少 {@code tool_call_id}，或非工具消息带了它时
     */
    static ChatRequest toChatRequest(CreateChatCompletionRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        List<FieldViolation> violations = new ArrayList<>();
        for (int i = 0; i < request.getMessages().size(); i++) {
            ChatCompletionMessage message = request.getMessages().get(i);
            ChatRole role = ChatRole.valueOf(message.getRole().name());
            if (role == ChatRole.TOOL && message.getToolCallId() == null) {
                violations.add(new FieldViolation("messages." + i + ".tool_call_id", "tool 消息必须带 tool_call_id"));
                continue;
            }
            if (role != ChatRole.ASSISTANT
                    && message.getToolCalls() != null
                    && !message.getToolCalls().isEmpty()) {
                violations.add(new FieldViolation("messages." + i + ".tool_calls", "只有 assistant 消息可以带 tool_calls"));
                continue;
            }
            List<ToolCall> calls = message.getToolCalls() == null
                    ? List.of()
                    : message.getToolCalls().stream()
                            .map(call -> new ToolCall(
                                    call.getId(),
                                    call.getFunction().getName(),
                                    call.getFunction().getArguments()))
                            .toList();
            messages.add(new ChatMessage(
                    role, message.getContent(), calls, role == ChatRole.TOOL ? message.getToolCallId() : null));
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(CommonErrorCode.VALIDATION_FAILED, "对话消息不合法", violations);
        }
        List<ToolDefinition> tools = request.getTools() == null
                ? List.of()
                : request.getTools().stream().map(ChatCompletionMapper::toTool).toList();
        Integer maxTokens =
                request.getMaxCompletionTokens() != null ? request.getMaxCompletionTokens() : request.getMaxTokens();
        Double temperature = request.getTemperature() == null
                ? null
                : request.getTemperature().doubleValue();
        return new ChatRequest(request.getModel(), messages, tools, maxTokens, temperature);
    }

    private static ToolDefinition toTool(ChatCompletionTool tool) {
        Map<String, Object> parameters = tool.getFunction().getParameters() == null
                ? NO_PARAMETERS
                : new LinkedHashMap<>(tool.getFunction().getParameters());
        return new ToolDefinition(
                tool.getFunction().getName(), tool.getFunction().getDescription(), parameters);
    }

    /**
     * 平台用量翻译为 OpenAI 的用量。
     *
     * @param usage 平台用量；可为空
     * @return 用量 DTO；没有用量时为空
     */
    static CompletionUsage toUsage(TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        return new CompletionUsage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.inputTokens() + usage.outputTokens(),
                new PromptTokensDetails(usage.cachedTokens()),
                new CompletionTokensDetails(usage.reasoningTokens()));
    }

    /**
     * 首包之后的错误在流中的表示：OpenAI 兼容服务以一条 {@code {"error": {...}}} 结束流。不含内部信息。
     *
     * @param error 错误
     * @return 错误对象
     */
    static Map<String, Object> errorFrame(Throwable error) {
        String code = "internal.error";
        String message = "模型调用失败";
        if (error instanceof MeridianException platform) {
            code = platform.errorCode().code();
            message = platform.detail() == null ? platform.errorCode().title() : platform.detail();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("type", code);
        body.put("code", code);
        return Map.of("error", body);
    }

    /**
     * 一次补全的公共字段：ID、创建时间与模型 ID，所有块与非流式结果相同。
     *
     * @param id 补全 ID（{@code chatcmpl-…}）
     * @param created 创建时间，Unix 秒
     * @param model 请求中的模型 ID
     */
    record Completion(String id, long created, String model) {}

    /**
     * 流式块的翻译：第一个块带 {@code role}，用量单独成块（只在调用方要求时输出）。
     *
     * <p>线程安全：不共享，流的信号由 Reactor 串行传递。
     */
    static final class ChunkWriter {

        private final Completion completion;
        private boolean roleSent;
        private TokenUsage usage;

        ChunkWriter(Completion completion) {
            this.completion = completion;
        }

        /**
         * 翻译一个增量块；只带用量的块不输出，用量留到最后。
         *
         * @param chunk 平台增量块
         * @return OpenAI 增量块；没有可输出的内容时为空
         */
        ChatCompletionChunk delta(ChatChunk chunk) {
            if (chunk.usage() != null) {
                usage = chunk.usage();
            }
            boolean hasDelta = chunk.textDelta() != null
                    || chunk.reasoningDelta() != null
                    || !chunk.toolCalls().isEmpty();
            if (!hasDelta && chunk.finishReason() == null) {
                return null;
            }
            ChatCompletionChunkDelta delta = new ChatCompletionChunkDelta();
            if (!roleSent) {
                delta.role(ChatCompletionChunkDelta.RoleEnum.ASSISTANT);
                roleSent = true;
            }
            delta.content(chunk.textDelta()).reasoningContent(chunk.reasoningDelta());
            if (!chunk.toolCalls().isEmpty()) {
                delta.toolCalls(chunk.toolCalls().stream()
                        .map(ChunkWriter::toolCallDelta)
                        .toList());
            }
            return base().addChoicesItem(new ChatCompletionChunkChoice(0, delta, chunk.finishReason()));
        }

        /**
         * 带用量的最后一块（{@code choices} 为空）。
         *
         * @return 用量块；供应商没有报告用量时为空
         */
        ChatCompletionChunk usageChunk() {
            return usage == null ? null : base().usage(toUsage(usage));
        }

        private ChatCompletionChunk base() {
            return new ChatCompletionChunk(
                    completion.id(),
                    ChatCompletionChunk.ObjectEnum.CHAT_COMPLETION_CHUNK,
                    completion.created(),
                    completion.model(),
                    new ArrayList<>());
        }

        private static ChatCompletionChunkToolCall toolCallDelta(ToolCallDelta delta) {
            ChatCompletionChunkToolCall call = new ChatCompletionChunkToolCall(delta.index()).id(delta.id());
            if (delta.id() != null) {
                call.type(ChatCompletionChunkToolCall.TypeEnum.FUNCTION);
            }
            if (delta.name() != null || delta.argumentsDelta() != null) {
                call.function(
                        new ChatCompletionChunkFunction().name(delta.name()).arguments(delta.argumentsDelta()));
            }
            return call;
        }
    }

    /**
     * 非流式的聚合：文本与思考拼接，工具调用按序号归并，取最后出现的用量与结束原因。
     *
     * <p>线程安全：不共享，流的信号由 Reactor 串行传递。
     */
    static final class Aggregate {

        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final Map<Integer, ToolCallBuilder> toolCalls = new TreeMap<>();
        private TokenUsage usage;
        private String finishReason;

        /**
         * 加入一个增量块。
         *
         * @param chunk 平台增量块
         * @return 本聚合
         */
        Aggregate add(ChatChunk chunk) {
            if (chunk.textDelta() != null) {
                text.append(chunk.textDelta());
            }
            if (chunk.reasoningDelta() != null) {
                reasoning.append(chunk.reasoningDelta());
            }
            for (ToolCallDelta delta : chunk.toolCalls()) {
                toolCalls
                        .computeIfAbsent(delta.index(), index -> new ToolCallBuilder())
                        .add(delta);
            }
            if (chunk.usage() != null) {
                usage = chunk.usage();
            }
            if (chunk.finishReason() != null) {
                finishReason = chunk.finishReason();
            }
            return this;
        }

        /**
         * 组装非流式结果。
         *
         * @param completion 补全的公共字段
         * @return 结果 DTO
         */
        cn.refinex.meridian.api.model.ChatCompletion toCompletion(Completion completion) {
            List<ChatCompletionMessageToolCall> calls =
                    toolCalls.values().stream().map(ToolCallBuilder::build).toList();
            ChatCompletionResponseMessage message = new ChatCompletionResponseMessage(
                    ChatCompletionResponseMessage.RoleEnum.ASSISTANT,
                    text.isEmpty() && !calls.isEmpty() ? null : text.toString(),
                    reasoning.isEmpty() ? null : reasoning.toString(),
                    calls.isEmpty() ? null : new ArrayList<>(calls));
            return new cn.refinex.meridian.api.model.ChatCompletion(
                    completion.id(),
                    cn.refinex.meridian.api.model.ChatCompletion.ObjectEnum.CHAT_COMPLETION,
                    completion.created(),
                    completion.model(),
                    new ArrayList<>(List.of(new ChatCompletionChoice(0, message, finishReason))),
                    toUsage(usage));
        }
    }

    /** 一个工具调用的归并：ID 与函数名取首次出现的值，入参片段按顺序拼接。 */
    private static final class ToolCallBuilder {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void add(ToolCallDelta delta) {
            if (id == null && delta.id() != null) {
                id = delta.id();
            }
            if (name == null && delta.name() != null) {
                name = delta.name();
            }
            if (delta.argumentsDelta() != null) {
                arguments.append(delta.argumentsDelta());
            }
        }

        ChatCompletionMessageToolCall build() {
            return new ChatCompletionMessageToolCall(
                    id == null ? "" : id,
                    ChatCompletionMessageToolCall.TypeEnum.FUNCTION,
                    new ChatCompletionFunctionCall(name == null ? "" : name, arguments.toString()));
        }
    }
}
