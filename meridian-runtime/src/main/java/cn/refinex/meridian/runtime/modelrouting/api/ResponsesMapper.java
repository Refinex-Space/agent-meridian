package cn.refinex.meridian.runtime.modelrouting.api;

import cn.refinex.meridian.api.model.CreateResponseRequest;
import cn.refinex.meridian.api.model.ModelResponse;
import cn.refinex.meridian.api.model.ResponseError;
import cn.refinex.meridian.api.model.ResponseIncompleteDetails;
import cn.refinex.meridian.api.model.ResponseInputTokensDetails;
import cn.refinex.meridian.api.model.ResponseOutputMessage;
import cn.refinex.meridian.api.model.ResponseOutputText;
import cn.refinex.meridian.api.model.ResponseOutputTokensDetails;
import cn.refinex.meridian.api.model.ResponseStreamEvent;
import cn.refinex.meridian.api.model.ResponseUsage;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatChunk;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatMessage;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatRequest;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatRole;
import cn.refinex.meridian.engine.spi.event.TokenUsage;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelRoutingErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * OpenAI Responses 子集与平台 {@code ModelInvoker} 之间的映射（{@code POST /v1/responses}，ADR-0050）：请求翻译与不支持参数的检查、
 * 非流式结果、流式事件序列。聚合复用对话补全的 {@link ChatCompletionMapper.Aggregate}。
 */
final class ResponsesMapper {

    /** OpenAI 的结束原因：达到输出长度上限，响应以 {@code incomplete} 结束。 */
    private static final String LENGTH = "length";

    private ResponsesMapper() {}

    /**
     * 请求翻译为平台的聊天请求。
     *
     * @param request 请求 DTO（未知字段已在解析时拒绝）
     * @return 聊天请求
     * @throws ValidationException 有不支持的参数或输入项（{@code model.unsupported_parameter}），或输入不合法
     *     （{@code request.validation_failed}）时
     */
    static ChatRequest toChatRequest(CreateResponseRequest request) {
        List<FieldViolation> unsupported = unsupportedParameters(request);
        List<FieldViolation> invalid = new ArrayList<>();
        List<ChatMessage> messages = new ArrayList<>();
        if (request.getInstructions() != null && !request.getInstructions().isEmpty()) {
            messages.add(new ChatMessage(ChatRole.SYSTEM, request.getInstructions(), List.of(), null));
        }
        Object input = request.getInput();
        if (input instanceof String text) {
            if (text.isEmpty()) {
                invalid.add(new FieldViolation("input", "不能为空"));
            } else {
                messages.add(new ChatMessage(ChatRole.USER, text, List.of(), null));
            }
        } else if (input instanceof List<?> items && !items.isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                ChatMessage message = message(items.get(i), "input[" + i + "]", unsupported, invalid);
                if (message != null) {
                    messages.add(message);
                }
            }
        } else {
            invalid.add(new FieldViolation("input", "必须是非空文本，或至少一条消息的数组"));
        }
        if (!unsupported.isEmpty()) {
            throw new ValidationException(
                    ModelRoutingErrorCode.UNSUPPORTED_PARAMETER, "请求含 /v1/responses 不支持的参数或输入项", unsupported);
        }
        if (!invalid.isEmpty()) {
            throw new ValidationException(CommonErrorCode.VALIDATION_FAILED, "输入不合法", invalid);
        }
        Double temperature = request.getTemperature() == null
                ? null
                : request.getTemperature().doubleValue();
        return new ChatRequest(request.getModel(), messages, List.of(), request.getMaxOutputTokens(), temperature);
    }

    /** 给出了的不支持参数，按规范中的顺序。 */
    private static List<FieldViolation> unsupportedParameters(CreateResponseRequest request) {
        Map<String, Supplier<Object>> parameters = new LinkedHashMap<>();
        parameters.put("tools", request::getTools);
        parameters.put("tool_choice", request::getToolChoice);
        parameters.put("parallel_tool_calls", request::getParallelToolCalls);
        parameters.put("previous_response_id", request::getPreviousResponseId);
        parameters.put("conversation", request::getConversation);
        parameters.put("reasoning", request::getReasoning);
        parameters.put("text", request::getText);
        parameters.put("top_p", request::getTopP);
        parameters.put("truncation", request::getTruncation);
        parameters.put("include", request::getInclude);
        parameters.put("metadata", request::getMetadata);
        parameters.put("background", request::getBackground);
        parameters.put("prompt", request::getPrompt);
        parameters.put("user", request::getUser);
        parameters.put("service_tier", request::getServiceTier);
        parameters.put("max_tool_calls", request::getMaxToolCalls);
        parameters.put("top_logprobs", request::getTopLogprobs);
        parameters.put("stream_options", request::getStreamOptions);
        List<FieldViolation> violations = new ArrayList<>();
        parameters.forEach((name, value) -> {
            if (value.get() != null) {
                violations.add(new FieldViolation(name, "不支持该参数（/v1/responses 只支持文本输入输出、流式与 usage）"));
            }
        });
        if (Boolean.TRUE.equals(request.getStore())) {
            violations.add(new FieldViolation("store", "平台不保存响应，只接受 false"));
        }
        return violations;
    }

    private static ChatMessage message(
            Object item, String field, List<FieldViolation> unsupported, List<FieldViolation> invalid) {
        if (!(item instanceof Map<?, ?> map)) {
            invalid.add(new FieldViolation(field, "必须是消息对象"));
            return null;
        }
        Object type = map.get("type");
        if (type != null && !"message".equals(type)) {
            unsupported.add(new FieldViolation(field + ".type", "不支持的输入项：" + type));
            return null;
        }
        ChatRole role = role(map.get("role"));
        if (role == null) {
            invalid.add(new FieldViolation(field + ".role", "必须是 user、assistant、system 或 developer"));
            return null;
        }
        for (Object key : map.keySet()) {
            if (!"type".equals(key) && !"role".equals(key) && !"content".equals(key)) {
                unsupported.add(new FieldViolation(field + "." + key, "不支持该字段"));
            }
        }
        String text = content(map.get("content"), field + ".content", unsupported, invalid);
        return text == null ? null : new ChatMessage(role, text, List.of(), null);
    }

    private static ChatRole role(Object role) {
        if (!(role instanceof String value)) {
            return null;
        }
        return switch (value) {
            case "user" -> ChatRole.USER;
            case "assistant" -> ChatRole.ASSISTANT;
            case "system", "developer" -> ChatRole.SYSTEM;
            default -> null;
        };
    }

    /** 消息内容拼成一段文本：文本块按顺序相连。 */
    private static String content(
            Object content, String field, List<FieldViolation> unsupported, List<FieldViolation> invalid) {
        if (content instanceof String text) {
            return text;
        }
        if (!(content instanceof List<?> parts) || parts.isEmpty()) {
            invalid.add(new FieldViolation(field, "必须是文本，或至少一块文本的数组"));
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (int j = 0; j < parts.size(); j++) {
            String where = field + "[" + j + "]";
            if (!(parts.get(j) instanceof Map<?, ?> part)) {
                invalid.add(new FieldViolation(where, "必须是内容块对象"));
                return null;
            }
            Object type = part.get("type");
            if (!"input_text".equals(type) && !"output_text".equals(type)) {
                unsupported.add(new FieldViolation(where + ".type", "不支持的内容块：" + type));
                return null;
            }
            if (!(part.get("text") instanceof String value)) {
                invalid.add(new FieldViolation(where + ".text", "必须是文本"));
                return null;
            }
            text.append(value);
        }
        return text.toString();
    }

    /**
     * 一次响应的公共字段。
     *
     * @param id 响应 ID（{@code resp_…}）
     * @param itemId 输出项 ID（{@code msg_…}）
     * @param createdAt 创建时间，Unix 秒
     * @param model 请求中的模型 ID
     */
    record Identity(String id, String itemId, long createdAt, String model) {}

    /**
     * 由聚合结果组装响应。
     *
     * @param identity 公共字段
     * @param aggregate 聚合结果
     * @return 响应 DTO
     */
    static ModelResponse completed(Identity identity, ChatCompletionMapper.Aggregate aggregate) {
        boolean incomplete = LENGTH.equals(aggregate.finishReason());
        return response(
                identity,
                incomplete ? ModelResponse.StatusEnum.INCOMPLETE : ModelResponse.StatusEnum.COMPLETED,
                List.of(item(
                        identity,
                        incomplete
                                ? ResponseOutputMessage.StatusEnum.INCOMPLETE
                                : ResponseOutputMessage.StatusEnum.COMPLETED,
                        List.of(part(aggregate.text())))),
                toUsage(aggregate.usage()),
                incomplete
                        ? new ResponseIncompleteDetails(ResponseIncompleteDetails.ReasonEnum.MAX_OUTPUT_TOKENS)
                        : null,
                null);
    }

    private static ModelResponse response(
            Identity identity,
            ModelResponse.StatusEnum status,
            List<ResponseOutputMessage> output,
            ResponseUsage usage,
            ResponseIncompleteDetails incomplete,
            ResponseError error) {
        return new ModelResponse(
                identity.id(),
                ModelResponse.ObjectEnum.RESPONSE,
                identity.createdAt(),
                status,
                identity.model(),
                new ArrayList<>(output),
                usage,
                incomplete,
                error);
    }

    private static ResponseOutputMessage item(
            Identity identity, ResponseOutputMessage.StatusEnum status, List<ResponseOutputText> content) {
        return new ResponseOutputMessage(
                ResponseOutputMessage.TypeEnum.MESSAGE,
                identity.itemId(),
                status,
                ResponseOutputMessage.RoleEnum.ASSISTANT,
                new ArrayList<>(content));
    }

    private static ResponseOutputText part(String text) {
        return new ResponseOutputText(ResponseOutputText.TypeEnum.OUTPUT_TEXT, text, new ArrayList<>());
    }

    private static ResponseUsage toUsage(TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        return new ResponseUsage(
                usage.inputTokens(),
                new ResponseInputTokensDetails(usage.cachedTokens()),
                usage.outputTokens(),
                new ResponseOutputTokensDetails(usage.reasoningTokens()),
                usage.totalTokens());
    }

    /**
     * 流式事件的生成：开头四个事件、文本增量、结尾四个事件，或首包之后出错时的 {@code response.failed}。{@code sequence_number} 从 0
     * 递增。
     *
     * <p>线程安全：不共享，流的信号由 Reactor 串行传递。
     */
    static final class StreamWriter {

        private final Identity identity;
        private final ChatCompletionMapper.Aggregate aggregate = new ChatCompletionMapper.Aggregate();
        private int sequence;

        StreamWriter(Identity identity) {
            this.identity = identity;
        }

        /**
         * 开头的事件：{@code response.created}、{@code response.in_progress}、{@code response.output_item.added}、
         * {@code response.content_part.added}。
         *
         * @return 事件
         */
        List<ResponseStreamEvent> start() {
            ModelResponse inProgress =
                    response(identity, ModelResponse.StatusEnum.IN_PROGRESS, List.of(), null, null, null);
            return List.of(
                    event(ResponseStreamEvent.TypeEnum.RESPONSE_CREATED).response(inProgress),
                    event(ResponseStreamEvent.TypeEnum.RESPONSE_IN_PROGRESS).response(inProgress),
                    event(ResponseStreamEvent.TypeEnum.RESPONSE_OUTPUT_ITEM_ADDED)
                            .outputIndex(0)
                            .item(item(identity, ResponseOutputMessage.StatusEnum.IN_PROGRESS, List.of())),
                    event(ResponseStreamEvent.TypeEnum.RESPONSE_CONTENT_PART_ADDED)
                            .itemId(identity.itemId())
                            .outputIndex(0)
                            .contentIndex(0)
                            .part(part("")));
        }

        /**
         * 一个增量块：有文本时给出 {@code response.output_text.delta}；用量与结束原因只记下。
         *
         * @param chunk 平台增量块
         * @return 事件；没有文本时为空
         */
        ResponseStreamEvent delta(ChatChunk chunk) {
            aggregate.add(chunk);
            if (chunk.textDelta() == null || chunk.textDelta().isEmpty()) {
                return null;
            }
            return event(ResponseStreamEvent.TypeEnum.RESPONSE_OUTPUT_TEXT_DELTA)
                    .itemId(identity.itemId())
                    .outputIndex(0)
                    .contentIndex(0)
                    .delta(chunk.textDelta());
        }

        /**
         * 结尾的事件：{@code response.output_text.done}、{@code response.content_part.done}、
         * {@code response.output_item.done}，最后是带用量的 {@code response.completed}（或 {@code response.incomplete}）。
         *
         * @return 事件
         */
        List<ResponseStreamEvent> finish() {
            ModelResponse done = completed(identity, aggregate);
            ResponseOutputMessage item = done.getOutput().getFirst();
            return List.of(
                    event(ResponseStreamEvent.TypeEnum.RESPONSE_OUTPUT_TEXT_DONE)
                            .itemId(identity.itemId())
                            .outputIndex(0)
                            .contentIndex(0)
                            .text(aggregate.text()),
                    event(ResponseStreamEvent.TypeEnum.RESPONSE_CONTENT_PART_DONE)
                            .itemId(identity.itemId())
                            .outputIndex(0)
                            .contentIndex(0)
                            .part(part(aggregate.text())),
                    event(ResponseStreamEvent.TypeEnum.RESPONSE_OUTPUT_ITEM_DONE)
                            .outputIndex(0)
                            .item(item),
                    event(
                                    done.getStatus() == ModelResponse.StatusEnum.INCOMPLETE
                                            ? ResponseStreamEvent.TypeEnum.RESPONSE_INCOMPLETE
                                            : ResponseStreamEvent.TypeEnum.RESPONSE_COMPLETED)
                            .response(done));
        }

        /**
         * 首包之后的错误：一条 {@code response.failed}，带已收到的文本与错误码。不含内部信息。
         *
         * @param error 错误
         * @return 事件
         */
        ResponseStreamEvent failed(Throwable error) {
            String code = CommonErrorCode.INTERNAL.code();
            String message = "模型调用失败";
            if (error instanceof MeridianException platform) {
                code = platform.errorCode().code();
                message = platform.detail() == null ? platform.errorCode().title() : platform.detail();
            }
            ModelResponse failed = response(
                    identity,
                    ModelResponse.StatusEnum.FAILED,
                    List.of(item(
                            identity, ResponseOutputMessage.StatusEnum.INCOMPLETE, List.of(part(aggregate.text())))),
                    toUsage(aggregate.usage()),
                    null,
                    new ResponseError(code, message));
            return event(ResponseStreamEvent.TypeEnum.RESPONSE_FAILED).response(failed);
        }

        private ResponseStreamEvent event(ResponseStreamEvent.TypeEnum type) {
            return new ResponseStreamEvent(type, sequence++);
        }
    }
}
