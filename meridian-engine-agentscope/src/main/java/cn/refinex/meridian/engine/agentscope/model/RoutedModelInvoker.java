package cn.refinex.meridian.engine.agentscope.model;

import cn.refinex.meridian.engine.spi.driver.ModelInvoker;
import cn.refinex.meridian.engine.spi.event.TokenUsage;
import cn.refinex.meridian.engine.spi.port.ModelGateway;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * {@link ModelInvoker} 的实现：OpenAI 兼容直通接口的聊天调用经 {@link RoutedModel}，与智能体的模型调用共用解析、限流、预扣、
 * 重试与结算（架构设计「端口」「模型调用」）。
 *
 * <p>每次调用构造一个只含主模型的 {@link RoutedModel}（直通调用没有备用模型、不压缩，构造它不做 I/O），调用方的最大输出与温度放进
 * 调用选项；用量记录的智能体版本为空，预算范围由 {@code PlatformContext} 决定（直通接口没有会话，只计租户、项目、API Key）。
 *
 * <p>增量翻译：2.0.4 的 OpenAI 兼容客户端把流式的工具调用拆成"带工具名的首块"与"工具名为 {@value #FRAGMENT_NAME} 的参数片段"，
 * 片段不带序号。供应商按顺序逐个输出工具调用，所以片段（以及与上一个调用 ID 相同的块）归到最近一个首块的序号上。
 *
 * <p>线程安全：无可变状态；每次调用的翻译状态在订阅内创建。
 */
public final class RoutedModelInvoker implements ModelInvoker {

    /** 2.0.4 {@code OpenAIResponseParser} 给工具调用参数片段的占位名。 */
    static final String FRAGMENT_NAME = "__fragment__";

    private final ModelGateway gateway;
    private final ProviderClients clients;
    private final RoutedModel.RetryPolicy retryPolicy;

    /**
     * 创建调用器。
     *
     * @param gateway 模型调用的平台侧服务
     * @param clients 底层客户端的登记与缓存，与智能体的模型工厂共享
     * @param retryPolicy 首包之前的重试策略
     */
    public RoutedModelInvoker(ModelGateway gateway, ProviderClients clients, RoutedModel.RetryPolicy retryPolicy) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        // 上下文窗口只供压缩使用，直通调用不压缩
        RoutedModel model = new RoutedModel(gateway, clients, retryPolicy, ModelSpec.of(request.modelId()), null, 1);
        GenerateOptions.Builder options = GenerateOptions.builder();
        if (request.maxTokens() != null) {
            options.maxTokens(request.maxTokens());
        }
        if (request.temperature() != null) {
            options.temperature(request.temperature());
        }
        List<Msg> messages =
                request.messages().stream().map(RoutedModelInvoker::toMsg).toList();
        List<ToolSchema> tools =
                request.tools().stream().map(RoutedModelInvoker::toSchema).toList();
        return Flux.defer(() -> {
            ChunkTranslator translator = new ChunkTranslator();
            return model.stream(messages, tools, options.build())
                    .map(translator::translate)
                    .filter(ChunkTranslator::hasContent);
        });
    }

    /** 平台的聊天消息翻译为 AgentScope 消息：工具调用为 {@link ToolUseBlock}，工具结果为 {@link ToolResultBlock}。 */
    static Msg toMsg(ChatMessage message) {
        String text = message.content() == null ? "" : message.content();
        return switch (message.role()) {
            case SYSTEM ->
                Msg.builder().role(MsgRole.SYSTEM).content(text(text)).build();
            case USER -> Msg.builder().role(MsgRole.USER).content(text(text)).build();
            case ASSISTANT -> {
                List<ContentBlock> blocks = new ArrayList<>();
                if (!text.isEmpty()) {
                    blocks.add(text(text));
                }
                for (ToolCall call : message.toolCalls()) {
                    blocks.add(ToolUseBlock.builder()
                            .id(call.id())
                            .name(call.name())
                            .input(arguments(call.arguments()))
                            .content(call.arguments())
                            .build());
                }
                yield Msg.builder().role(MsgRole.ASSISTANT).content(blocks).build();
            }
            case TOOL ->
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(ToolResultBlock.builder()
                                .id(message.toolCallId())
                                .output(List.of(text(text)))
                                .build())
                        .build();
        };
    }

    static ToolSchema toSchema(ToolDefinition tool) {
        return ToolSchema.builder()
                .name(tool.name())
                .description(tool.description() == null ? "" : tool.description())
                .parameters(tool.parameters())
                .build();
    }

    private static TextBlock text(String text) {
        return TextBlock.builder().text(text).build();
    }

    /** 解析工具调用的入参；不是 JSON 对象时为空映射，原文仍经 {@code content} 原样发给供应商。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(String json) {
        try {
            Map<String, Object> parsed = JsonUtils.getJsonCodec().fromJson(json, Map.class);
            return parsed == null ? Map.of() : new LinkedHashMap<>(parsed);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * 一次调用的增量翻译：AgentScope 的 {@link ChatResponse} 翻译为平台的 {@link ChatChunk}，维护工具调用的序号。
     *
     * <p>线程安全：不共享，流的信号由 Reactor 串行传递。
     */
    static final class ChunkTranslator {

        private int nextToolIndex;
        private String lastToolCallId;

        ChatChunk translate(ChatResponse response) {
            StringBuilder text = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            List<ToolCallDelta> toolCalls = new ArrayList<>();
            List<ContentBlock> content = response.getContent() == null ? List.of() : response.getContent();
            for (ContentBlock block : content) {
                switch (block) {
                    case TextBlock t when t.getText() != null -> text.append(t.getText());
                    case ThinkingBlock t when t.getThinking() != null -> reasoning.append(t.getThinking());
                    case ToolUseBlock call
                    when continues(call) -> {
                        if (call.getContent() != null && !call.getContent().isEmpty()) {
                            toolCalls.add(new ToolCallDelta(nextToolIndex - 1, null, null, call.getContent()));
                        }
                    }
                    case ToolUseBlock call -> {
                        lastToolCallId = call.getId();
                        toolCalls.add(
                                new ToolCallDelta(nextToolIndex++, call.getId(), call.getName(), startArguments(call)));
                    }
                    default -> {
                        // 其他内容块（图片、音频）不在直通接口的输出范围内
                    }
                }
            }
            return new ChatChunk(
                    text.isEmpty() ? null : text.toString(),
                    reasoning.isEmpty() ? null : reasoning.toString(),
                    toolCalls,
                    usage(response.getUsage()),
                    response.getFinishReason());
        }

        /** 是否是上一个工具调用的后续片段。 */
        private boolean continues(ToolUseBlock call) {
            if (nextToolIndex == 0) {
                return false;
            }
            return FRAGMENT_NAME.equals(call.getName())
                    || (call.getId() != null && call.getId().equals(lastToolCallId));
        }

        /** 首块的参数：流式时是供应商给出的原文片段；非流式的完整调用没有原文时由入参序列化。 */
        private static String startArguments(ToolUseBlock call) {
            if (call.getContent() != null) {
                return call.getContent();
            }
            return call.getInput() == null || call.getInput().isEmpty()
                    ? null
                    : JsonUtils.getJsonCodec().toJson(call.getInput());
        }

        static boolean hasContent(ChatChunk chunk) {
            return chunk.textDelta() != null
                    || chunk.reasoningDelta() != null
                    || !chunk.toolCalls().isEmpty()
                    || chunk.usage() != null
                    || chunk.finishReason() != null;
        }

        private static TokenUsage usage(ChatUsage usage) {
            if (usage == null) {
                return null;
            }
            return new TokenUsage(
                    Math.max(0, usage.getInputTokens()),
                    Math.max(0, usage.getOutputTokens()),
                    Math.max(0, usage.getCachedTokens()),
                    Math.max(0, usage.getCacheCreationTokens()),
                    Math.max(0, usage.getReasoningTokens()));
        }
    }
}
