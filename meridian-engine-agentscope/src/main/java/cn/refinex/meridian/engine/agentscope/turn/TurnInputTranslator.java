package cn.refinex.meridian.engine.agentscope.turn;

import cn.refinex.meridian.engine.spi.engine.input.CustomToolResult;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.ToolConfirmation;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把一轮的平台输入翻译为 AgentScope 的调用入参，见架构设计「内核适配层」「待处理动作」。
 *
 * <p>四种输入各自翻译为一种互不混合的消息：AgentScope 发现上下文中有待确认的调用时，直接进入确认分支、丢弃同批的
 * 普通消息（ADR-0016），所以审批结果与自定义工具结果都不与用户文本放在一起。
 * <ul>
 *   <li>{@code NewMessages}：每条用户消息一个 {@link UserMessage}。
 *   <li>{@code Confirmations}：一条没有内容块的用户消息，元数据 {@link Msg#METADATA_CONFIRM_RESULTS} 中是在 Java 侧构造的
 *       {@link ConfirmResult} 实例（AgentScope 只认实例，不认反序列化出来的 Map）。
 *   <li>{@code CustomToolResults}：一条 {@link ToolResultMessage}，每个结果一个 {@link ToolResultBlock}。
 *   <li>{@code RecoveryOnly}：没有入参，恢复说明由 {@code RecoveryNoteMiddleware} 在推理前注入（T06）。
 * </ul>
 */
public final class TurnInputTranslator {

    private TurnInputTranslator() {}

    /**
     * 翻译输入。
     *
     * @param input 本轮输入
     * @return 调用 {@code streamEvents} 的消息；{@code RecoveryOnly} 时为空列表
     */
    static List<Msg> messages(TurnInput input) {
        return switch (input) {
            case TurnInput.NewMessages m ->
                m.messages().stream().map(TurnInputTranslator::userMessage).toList();
            case TurnInput.Confirmations c -> List.of(confirmations(c.confirmations()));
            case TurnInput.CustomToolResults r ->
                List.of(new ToolResultMessage(r.results().stream()
                        .map(TurnInputTranslator::toolResult)
                        .toList()));
            case TurnInput.RecoveryOnly _ -> List.of();
        };
    }

    /**
     * 本轮输入中被批准的工具调用 ID：这些调用的结果 {@code permission} 为 {@code approved}（架构设计「事件映射」）。
     *
     * @param input 本轮输入
     * @return 工具调用 ID；不是确认输入时为空集合
     */
    static Set<String> approvedToolUseIds(TurnInput input) {
        if (input instanceof TurnInput.Confirmations c) {
            return c.confirmations().stream()
                    .filter(ToolConfirmation::approved)
                    .map(ToolConfirmation::toolUseId)
                    .collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    /**
     * 会话声明的自定义工具，以 {@link SchemaOnlyTool} 经 {@link ToolRequestConfig} 注入本次调用，合并模式
     * {@link ToolMergeMode#MERGE_EXTERNAL_PRIORITY}（架构设计「待处理动作」中的"自定义工具"）。共享的 Toolkit 不被修改。
     *
     * @param declarations 自定义工具声明
     * @return 请求级工具配置；没有声明时为空，表示沿用共享的 Toolkit
     */
    static ToolRequestConfig toolRequestConfig(List<CustomToolDeclaration> declarations) {
        if (declarations.isEmpty()) {
            return null;
        }
        Map<String, SchemaOnlyTool> tools = new LinkedHashMap<>();
        for (CustomToolDeclaration declaration : declarations) {
            tools.put(
                    declaration.name(),
                    new SchemaOnlyTool(declaration.name(), declaration.description(), declaration.inputSchema()));
        }
        return new ToolRequestConfig(tools, ToolMergeMode.MERGE_EXTERNAL_PRIORITY);
    }

    private static Msg userMessage(InboundMessage message) {
        return new UserMessage(message.content().stream()
                .map(TurnInputTranslator::contentBlock)
                .toList());
    }

    private static Msg confirmations(List<ToolConfirmation> confirmations) {
        List<ConfirmResult> results = confirmations.stream()
                .map(c -> new ConfirmResult(
                        c.approved(),
                        // 批准时 AgentScope 用这个调用整体替换原来的调用，所以带完整的工具名与入参；2.0.4 的
                        // ToolExecutor 按 content（入参 JSON 原文）做 schema 校验，content 为空会被判为参数缺失
                        new ToolUseBlock(
                                c.toolUseId(),
                                c.toolName(),
                                c.input(),
                                JsonUtils.getJsonCodec().toJson(c.input()),
                                null),
                        PermissionTranslator.toRules(c.rememberedRules()),
                        c.approved() ? null : c.denyReason()))
                .toList();
        return Msg.builder()
                .role(MsgRole.USER)
                .content(List.of())
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
    }

    private static ToolResultBlock toolResult(CustomToolResult result) {
        return ToolResultBlock.builder()
                .id(result.toolUseId())
                .name(result.toolName())
                .output(result.content().stream()
                        .map(TurnInputTranslator::contentBlock)
                        .toList())
                .state(result.error() ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                .build();
    }

    /**
     * 把平台的内容块翻译为 AgentScope 的内容块，用户消息、steer 消息与自定义工具结果共用。
     *
     * @param content 平台内容块
     * @return AgentScope 内容块
     */
    public static ContentBlock contentBlock(MessageContent content) {
        return switch (content) {
            case MessageContent.Text text ->
                TextBlock.builder().text(text.text()).build();
            case MessageContent.Image image
            when image.url() != null ->
                ImageBlock.builder()
                        .source(URLSource.builder()
                                .url(image.url())
                                .mimeType(image.mediaType())
                                .build())
                        .build();
            // TODO(T17b): 轮次开始时把引用的文件放进工作区后，这里改为工作区路径
            case MessageContent.Image image -> fileReference(image.fileId(), null, image.mediaType());
            case MessageContent.FileRef file -> fileReference(file.fileId(), file.filename(), file.mediaType());
        };
    }

    /** 平台文件以文本引用告诉模型；文件内容的放置由 T17b 负责。 */
    private static ContentBlock fileReference(String fileId, String filename, String mediaType) {
        StringBuilder text = new StringBuilder("[文件 ").append(fileId);
        if (filename != null) {
            text.append(' ').append(filename);
        }
        if (mediaType != null) {
            text.append(" (").append(mediaType).append(')');
        }
        return TextBlock.builder().text(text.append(']').toString()).build();
    }
}
