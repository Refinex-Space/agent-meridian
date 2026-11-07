package cn.refinex.meridian.engine.agentscope.events;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformStopRequest;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.error.BudgetExceededException;
import cn.refinex.meridian.engine.spi.error.RetryableTurnException;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent.PermissionOutcome;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent.ToolResultState;
import cn.refinex.meridian.engine.spi.event.BlobRef;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent.RescheduleReason;
import cn.refinex.meridian.engine.spi.event.SessionEvent.RetryStatus;
import cn.refinex.meridian.engine.spi.event.SpanEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.StreamEvent;
import cn.refinex.meridian.engine.spi.event.StreamEvent.BlockKind;
import cn.refinex.meridian.engine.spi.event.TokenUsage;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.DataBlockDeltaEvent;
import io.agentscope.core.event.DataBlockEndEvent;
import io.agentscope.core.event.DataBlockStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.tool.PlanModeTools;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 一轮的事件映射器：把 AgentScope 的 {@link AgentEvent} 翻译为平台的 {@link EngineEvent}，按架构设计「事件映射」表。
 *
 * <p>AgentScope 的结束类事件只带 ID 不带内容，所以映射器按块与工具调用 ID 累积增量，在结束时输出完整内容：
 * <ul>
 *   <li>文本、思考、数据块：键为 {@code replyId} 加 {@code blockId}。2.0.4 的块 ID 在每次模型调用内从 {@code text}、
 *       {@code text-2} 重新编号，只用 {@code blockId} 会让多次推理的块相撞，平台事件中的块 ID 取
 *       {@code {replyId}:{blockId}}。
 *   <li>工具调用：按工具调用 ID 累积入参片段，在该次模型调用的 {@code MODEL_CALL_END} 时按首次出现的顺序输出
 *       {@code agent.tool_use}。2.0.4 遇到新的工具调用 ID 会先结束此前所有调用，并行调用的片段交错到达时，
 *       同一个 ID 会多次收到 {@code TOOL_CALL_START}/{@code TOOL_CALL_END}，只有模型调用结束时入参才完整（ADR-0026）。
 *   <li>工具结果：按工具调用 ID 累积文本与数据片段，{@code TOOL_RESULT_END} 时输出 {@code agent.tool_result}；
 *       超过 {@link BlobStorePort#INLINE_LIMIT_BYTES} 的结果经 {@link BlobStorePort} 写入大对象存储，事件中放引用。
 * </ul>
 *
 * <p>终态：流正常结束时 {@link #complete()} 按 {@link StopReasonMapping} 输出唯一的终态事件；出错时
 * {@link #fail(Throwable)} 先输出 {@code session.error} 再输出终态，节点下线输出 {@code session.status_rescheduled}
 * 且不输出错误，租约丢失则不输出任何事件、原样结束（ADR-0026）。订阅被取消时两者都不会被调用。
 *
 * <p>子线程事件（{@code source} 不为空）只记日志，由 T13 的 {@code SpawnSubagentTool} 经 {@code EventSinkPort} 写入。
 *
 * <p>线程安全：不支持并发调用。引擎以 {@code concatMap} 串行调用 {@link #map}，Reactor 保证相邻信号之间的可见性。
 */
public final class TurnEventMapper {

    private static final Logger log = LoggerFactory.getLogger(TurnEventMapper.class);

    /** 大于内联上限的工具结果写入大对象存储时的媒体类型。 */
    static final String BLOB_CONTENT_TYPE = "text/plain; charset=utf-8";

    /** 未预期错误给调用方的说明，不暴露内部信息；排查以日志中的轮次 ID 关联。 */
    static final String INTERNAL_ERROR_MESSAGE = "本轮执行失败";

    private final Settings settings;
    private final BlobStorePort blobs;

    private final Map<String, StringBuilder> blocks = new HashMap<>();
    private final Map<String, Map<String, ToolCallBuffer>> toolCalls = new HashMap<>();
    private final Map<String, ToolResultBuffer> toolResults = new HashMap<>();
    private final List<PendingAction> pendingActions = new ArrayList<>();
    private GenerateReason generateReason;

    /**
     * 创建一轮的映射器。
     *
     * @param settings 本轮的映射参数
     * @param blobs 大对象存储
     */
    public TurnEventMapper(Settings settings, BlobStorePort blobs) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
    }

    /**
     * 映射一个 AgentScope 事件。
     *
     * @param event AgentScope 事件
     * @return 平台事件，按输出顺序；可能为空
     */
    public Flux<EngineEvent> map(AgentEvent event) {
        if (event.getSource() != null) {
            log.debug("子线程事件不在主线程流上映射 type={} source={}", event.getType(), event.getSource());
            return Flux.empty();
        }
        return switch (event.getType()) {
            case AGENT_START,
                    AGENT_END,
                    ALL_TOOLS_DENIED,
                    USER_CONFIRM_RESULT,
                    EXTERNAL_EXECUTION_RESULT,
                    REQUEST_STOP -> Flux.empty();
            case EXCEED_MAX_ITERS -> {
                log.info("本轮达到最大推理次数 maxIters={}", settings.maxIters());
                yield Flux.empty();
            }
            case AGENT_RESULT -> {
                AgentResultEvent result = (AgentResultEvent) event;
                generateReason =
                        result.getResult() == null ? null : result.getResult().getGenerateReason();
                yield Flux.empty();
            }
            case MODEL_CALL_START ->
                Flux.just(new SpanEvent.ModelRequestStart(
                        ((ModelCallStartEvent) event).getReplyId(), settings.modelId()));
            case MODEL_CALL_END -> modelCallEnd((ModelCallEndEvent) event);
            case TEXT_BLOCK_START ->
                startBlock(
                        ((TextBlockStartEvent) event).getReplyId(),
                        ((TextBlockStartEvent) event).getBlockId(),
                        BlockKind.TEXT);
            case TEXT_BLOCK_DELTA -> {
                TextBlockDeltaEvent delta = (TextBlockDeltaEvent) event;
                yield appendBlock(delta.getReplyId(), delta.getBlockId(), BlockKind.TEXT, delta.getDelta(), true);
            }
            case TEXT_BLOCK_END -> {
                TextBlockEndEvent end = (TextBlockEndEvent) event;
                String text = takeBlock(end.getReplyId(), end.getBlockId());
                yield text.isEmpty()
                        ? Flux.empty()
                        : Flux.just(new AgentOutputEvent.Message(
                                blockId(end.getReplyId(), end.getBlockId()), List.of(new MessageContent.Text(text))));
            }
            case THINKING_BLOCK_START ->
                startBlock(
                        ((ThinkingBlockStartEvent) event).getReplyId(),
                        ((ThinkingBlockStartEvent) event).getBlockId(),
                        BlockKind.THINKING);
            case THINKING_BLOCK_DELTA -> {
                ThinkingBlockDeltaEvent delta = (ThinkingBlockDeltaEvent) event;
                // 不暴露思考内容时，增量也不能出现在流上
                yield appendBlock(
                        delta.getReplyId(),
                        delta.getBlockId(),
                        BlockKind.THINKING,
                        delta.getDelta(),
                        settings.exposeThinking());
            }
            case THINKING_BLOCK_END -> {
                ThinkingBlockEndEvent end = (ThinkingBlockEndEvent) event;
                String text = takeBlock(end.getReplyId(), end.getBlockId());
                yield Flux.just(new AgentOutputEvent.Thinking(
                        blockId(end.getReplyId(), end.getBlockId()), settings.exposeThinking() ? text : null));
            }
            // 2.0.4 定义了数据块事件但没有任何地方发出；按表映射为内容为数据块的 agent.message
            case DATA_BLOCK_START ->
                startBlock(
                        ((DataBlockStartEvent) event).getReplyId(),
                        ((DataBlockStartEvent) event).getBlockId(),
                        BlockKind.DATA);
            case DATA_BLOCK_DELTA -> {
                DataBlockDeltaEvent delta = (DataBlockDeltaEvent) event;
                yield appendBlock(delta.getReplyId(), delta.getBlockId(), BlockKind.DATA, delta.getDelta(), true);
            }
            case DATA_BLOCK_END -> {
                DataBlockEndEvent end = (DataBlockEndEvent) event;
                String data = takeBlock(end.getReplyId(), end.getBlockId());
                yield Flux.just(new AgentOutputEvent.Message(
                        blockId(end.getReplyId(), end.getBlockId()), List.of(new MessageContent.Text(data))));
            }
            case TOOL_CALL_START -> toolCallStart((ToolCallStartEvent) event);
            case TOOL_CALL_DELTA -> toolCallDelta((ToolCallDeltaEvent) event);
            // 入参在模型调用结束时才完整，见类注释
            case TOOL_CALL_END -> Flux.empty();
            case TOOL_RESULT_START -> {
                ToolResultStartEvent start = (ToolResultStartEvent) event;
                toolResults.put(start.getToolCallId(), new ToolResultBuffer(start.getToolCallName()));
                yield Flux.just(new StreamEvent.EventStart(
                        start.getToolCallId(), BlockKind.TOOL_RESULT, start.getToolCallName()));
            }
            case TOOL_RESULT_TEXT_DELTA -> {
                ToolResultTextDeltaEvent delta = (ToolResultTextDeltaEvent) event;
                resultBuffer(delta.getToolCallId(), delta.getToolCallName()).appendText(delta.getDelta());
                yield delta.getDelta() == null || delta.getDelta().isEmpty()
                        ? Flux.empty()
                        : Flux.just(new StreamEvent.EventDelta(
                                delta.getToolCallId(), BlockKind.TOOL_RESULT, delta.getDelta()));
            }
            case TOOL_RESULT_DATA_DELTA -> {
                ToolResultDataDeltaEvent delta = (ToolResultDataDeltaEvent) event;
                MessageContent content = toMessageContent(delta.getData());
                resultBuffer(delta.getToolCallId(), delta.getToolCallName()).append(content);
                yield Flux.just(
                        new StreamEvent.EventDelta(delta.getToolCallId(), BlockKind.TOOL_RESULT, textOf(content)));
            }
            case TOOL_RESULT_END -> toolResultEnd((ToolResultEndEvent) event);
            case REQUIRE_USER_CONFIRM -> {
                for (ToolUseBlock call : ((RequireUserConfirmEvent) event).getToolCalls()) {
                    PendingAction.Type type = PlanModeTools.PLAN_EXIT.equals(call.getName())
                            ? PendingAction.Type.PLAN_EXIT
                            : PendingAction.Type.TOOL_CONFIRMATION;
                    pendingActions.add(pendingAction(type, call));
                }
                yield Flux.empty();
            }
            case REQUIRE_EXTERNAL_EXECUTION -> {
                List<EngineEvent> uses = new ArrayList<>();
                for (ToolUseBlock call : ((RequireExternalExecutionEvent) event).getToolCalls()) {
                    uses.add(new AgentOutputEvent.CustomToolUse(call.getId(), call.getName(), call.getInput()));
                    pendingActions.add(pendingAction(PendingAction.Type.CUSTOM_TOOL_RESULT, call));
                }
                yield Flux.fromIterable(uses);
            }
            case SUBAGENT_EXPOSED, HINT_BLOCK, CUSTOM -> {
                log.debug("忽略不映射的事件 type={}", event.getType());
                yield Flux.empty();
            }
        };
    }

    /**
     * 事件流正常结束：按最后一次调用结果的 {@link GenerateReason} 输出唯一的终态事件。
     *
     * @return 终态事件
     */
    public EngineEvent complete() {
        StopReasonMapping.Outcome outcome = StopReasonMapping.of(generateReason);
        return switch (outcome.kind()) {
            case IDLE -> new SessionEvent.StatusIdle(outcome.stopReason());
            case STOP_REQUESTED ->
                new SessionEvent.StatusIdle(settings.platformStop().reason().orElse(outcome.stopReason()));
            case REQUIRES_ACTION -> {
                if (pendingActions.isEmpty()) {
                    log.warn("本轮以 {} 结束却没有待处理动作，按 error 结束", generateReason);
                    yield new SessionEvent.StatusIdle(StopReason.ERROR);
                }
                yield new SessionEvent.StatusRequiresAction(pendingActions);
            }
            case UNEXPECTED -> {
                log.warn("本轮的结束原因不应出现，按 error 结束 generateReason={}", generateReason);
                yield new SessionEvent.StatusIdle(StopReason.ERROR);
            }
        };
    }

    /**
     * 事件流以错误结束：按架构设计「状态机」「事件映射」与「置回队列的三种情况」翻译为事件。
     *
     * <ul>
     *   <li>{@link LeaseLostException}：本节点已不拥有这一轮，不输出任何事件，原样以错误结束。
     *   <li>{@link AgentShuttingDownException}：节点下线，只输出 {@code session.status_rescheduled}（{@code node_shutdown}）。
     *   <li>{@link BudgetExceededException}：{@code session.error}（{@code terminal}）加 {@code stop_reason=budget_exceeded}。
     *   <li>{@link RetryableTurnException}：{@code session.error}（{@code retryable}）加
     *       {@code session.status_rescheduled}（{@code retryable_error}）。
     *   <li>其他异常：{@code session.error}（{@code terminal}）加 {@code stop_reason=error}；平台异常带自己的错误码与说明，
     *       未预期的异常用 {@code internal.error}，说明不含内部信息。
     * </ul>
     *
     * @param error 事件流的错误
     * @return 要输出的事件；租约丢失时为原错误
     */
    public Flux<EngineEvent> fail(Throwable error) {
        if (causeOf(error, LeaseLostException.class) != null) {
            return Flux.error(error);
        }
        if (causeOf(error, AgentShuttingDownException.class) != null) {
            log.info("节点下线，本轮已保存中途状态并置回队列");
            return Flux.just(new SessionEvent.StatusRescheduled(RescheduleReason.NODE_SHUTDOWN));
        }
        BudgetExceededException budget = causeOf(error, BudgetExceededException.class);
        if (budget != null) {
            log.info("预算不足，本轮结束 code={}", budget.errorCode().code());
            return Flux.just(
                    sessionError(budget, RetryStatus.TERMINAL),
                    new SessionEvent.StatusIdle(StopReason.BUDGET_EXCEEDED));
        }
        RetryableTurnException retryable = causeOf(error, RetryableTurnException.class);
        if (retryable != null) {
            log.warn("本轮遇到可重试的错误，置回队列 code={}", retryable.errorCode().code());
            return Flux.just(
                    sessionError(retryable, RetryStatus.RETRYABLE),
                    new SessionEvent.StatusRescheduled(RescheduleReason.RETRYABLE_ERROR));
        }
        MeridianException platform = causeOf(error, MeridianException.class);
        if (platform != null) {
            log.warn("本轮因平台错误结束 code={}", platform.errorCode().code(), error);
            return Flux.just(
                    sessionError(platform, RetryStatus.TERMINAL), new SessionEvent.StatusIdle(StopReason.ERROR));
        }
        log.error("本轮因未预期的错误结束", error);
        return Flux.just(
                new SessionEvent.Error(CommonErrorCode.INTERNAL.code(), INTERNAL_ERROR_MESSAGE, RetryStatus.TERMINAL),
                new SessionEvent.StatusIdle(StopReason.ERROR));
    }

    private Flux<EngineEvent> modelCallEnd(ModelCallEndEvent end) {
        List<EngineEvent> events = new ArrayList<>();
        Map<String, ToolCallBuffer> calls = toolCalls.remove(end.getReplyId());
        if (calls != null) {
            calls.forEach((id, call) -> {
                EngineEvent use = toolUse(id, call);
                if (use != null) {
                    events.add(use);
                }
            });
        }
        events.add(new SpanEvent.ModelRequestEnd(end.getReplyId(), settings.modelId(), tokenUsage(end.getUsage())));
        return Flux.fromIterable(events);
    }

    private Flux<EngineEvent> toolCallStart(ToolCallStartEvent start) {
        Map<String, ToolCallBuffer> calls = toolCalls.computeIfAbsent(start.getReplyId(), k -> new LinkedHashMap<>());
        if (calls.containsKey(start.getToolCallId())) {
            // 交错到达时同一调用会再次"开始"，流上只出现一次 event_start
            return Flux.empty();
        }
        calls.put(start.getToolCallId(), new ToolCallBuffer(start.getToolCallName()));
        return Flux.just(
                new StreamEvent.EventStart(start.getToolCallId(), BlockKind.TOOL_USE, start.getToolCallName()));
    }

    private Flux<EngineEvent> toolCallDelta(ToolCallDeltaEvent delta) {
        Map<String, ToolCallBuffer> calls = toolCalls.computeIfAbsent(delta.getReplyId(), k -> new LinkedHashMap<>());
        calls.computeIfAbsent(delta.getToolCallId(), k -> new ToolCallBuffer(delta.getToolCallName()))
                .input()
                .append(delta.getDelta());
        return Flux.just(new StreamEvent.EventDelta(delta.getToolCallId(), BlockKind.TOOL_USE, delta.getDelta()));
    }

    /** 拼装后的工具调用事件；自定义工具返回空，它在挂起时以 {@code agent.custom_tool_use} 输出。 */
    private EngineEvent toolUse(String toolUseId, ToolCallBuffer call) {
        if (settings.customToolNames().contains(call.name())) {
            return null;
        }
        Map<String, Object> input = parseInput(toolUseId, call);
        String mcpServer = mcpServer(call.name());
        return mcpServer == null
                ? new AgentOutputEvent.ToolUse(toolUseId, call.name(), input)
                : new AgentOutputEvent.McpToolUse(toolUseId, call.name(), mcpServer, input);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseInput(String toolUseId, ToolCallBuffer call) {
        String json = call.input().toString();
        if (json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JsonUtils.getJsonCodec().fromJson(json, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            // 与 AgentScope 的累积器一致：无法解析的入参按空入参处理；不记录入参正文
            log.warn(
                    "工具调用的入参不是合法的 JSON 对象，按空入参输出 toolUseId={} tool={} length={}",
                    toolUseId,
                    call.name(),
                    json.length());
            return Map.of();
        }
    }

    private Flux<EngineEvent> toolResultEnd(ToolResultEndEvent end) {
        ToolResultBuffer buffer = toolResults.remove(end.getToolCallId());
        String name = end.getToolCallName() != null ? end.getToolCallName() : buffer == null ? "" : buffer.name();
        if (end.getState() == io.agentscope.core.message.ToolResultState.RUNNING
                && settings.customToolNames().contains(name)) {
            // 自定义工具在 Toolkit 中挂起，结果由业务方经 user.custom_tool_result 回传，不输出 running 结果
            return Flux.empty();
        }
        ToolResultState state = ToolResultState.valueOf(end.getState().name());
        PermissionOutcome permission = state == ToolResultState.DENIED
                ? PermissionOutcome.DENIED
                : settings.approvedToolUseIds().contains(end.getToolCallId())
                        ? PermissionOutcome.APPROVED
                        : PermissionOutcome.ALLOWED;
        List<MessageContent> content = buffer == null ? List.of() : buffer.content();
        String mcpServer = mcpServer(name);
        byte[] bytes = inlineSize(content);
        if (bytes == null) {
            return Flux.just(toolResult(end.getToolCallId(), name, mcpServer, state, permission, content, null));
        }
        return blobs.put(BLOB_CONTENT_TYPE, bytes)
                .map(ref -> toolResult(end.getToolCallId(), name, mcpServer, state, permission, List.of(), ref))
                .flux();
    }

    private static EngineEvent toolResult(
            String toolUseId,
            String name,
            String mcpServer,
            ToolResultState state,
            PermissionOutcome permission,
            List<MessageContent> content,
            BlobRef ref) {
        return mcpServer == null
                ? new AgentOutputEvent.ToolResult(toolUseId, name, state, permission, content, ref)
                : new AgentOutputEvent.McpToolResult(toolUseId, name, mcpServer, state, permission, content, ref);
    }

    /** 结果超过内联上限时返回写入大对象存储的内容（各块的文本表示依次拼接），否则返回空。 */
    private static byte[] inlineSize(List<MessageContent> content) {
        StringBuilder text = new StringBuilder();
        for (MessageContent block : content) {
            text.append(textOf(block));
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        return bytes.length > BlobStorePort.INLINE_LIMIT_BYTES ? bytes : null;
    }

    private ToolResultBuffer resultBuffer(String toolUseId, String name) {
        return toolResults.computeIfAbsent(toolUseId, k -> new ToolResultBuffer(name));
    }

    private Flux<EngineEvent> startBlock(String replyId, String blockId, BlockKind kind) {
        blocks.put(blockId(replyId, blockId), new StringBuilder());
        return Flux.just(new StreamEvent.EventStart(blockId(replyId, blockId), kind, null));
    }

    private Flux<EngineEvent> appendBlock(
            String replyId, String blockId, BlockKind kind, String delta, boolean stream) {
        if (delta == null || delta.isEmpty()) {
            return Flux.empty();
        }
        blocks.computeIfAbsent(blockId(replyId, blockId), k -> new StringBuilder())
                .append(delta);
        return stream ? Flux.just(new StreamEvent.EventDelta(blockId(replyId, blockId), kind, delta)) : Flux.empty();
    }

    private String takeBlock(String replyId, String blockId) {
        StringBuilder text = blocks.remove(blockId(replyId, blockId));
        return text == null ? "" : text.toString();
    }

    private PendingAction pendingAction(PendingAction.Type type, ToolUseBlock call) {
        return new PendingAction(
                Ids.newId(IdPrefix.PENDING_ACTION), type, call.getId(), call.getName(), call.getInput(), null);
    }

    private static SessionEvent.Error sessionError(MeridianException error, RetryStatus status) {
        String message = error.detail() == null || error.detail().isBlank()
                ? error.errorCode().title()
                : error.detail();
        return new SessionEvent.Error(error.errorCode().code(), message, status);
    }

    /** 平台事件中的块 ID：2.0.4 的块 ID 只在一次模型调用内唯一，见类注释。 */
    static String blockId(String replyId, String blockId) {
        return replyId + ":" + blockId;
    }

    /** MCP 工具全名 {@code mcp__{server}__{tool}} 中的服务名；不是 MCP 工具时为空。 */
    static String mcpServer(String toolName) {
        if (toolName == null || !PlatformToolNames.isMcpTool(toolName)) {
            return null;
        }
        String rest = toolName.substring(PlatformToolNames.MCP_PREFIX.length());
        int separator = rest.indexOf("__");
        return separator > 0 ? rest.substring(0, separator) : null;
    }

    private static TokenUsage tokenUsage(ChatUsage usage) {
        if (usage == null) {
            return null;
        }
        return new TokenUsage(
                usage.getInputTokens(),
                usage.getOutputTokens(),
                usage.getCachedTokens(),
                usage.getCacheCreationTokens(),
                usage.getReasoningTokens());
    }

    /** AgentScope 内容块转平台内容块：文本与 URL 图片按原样，其余（base64 图片、音视频、数据）以 JSON 文本表示。 */
    static MessageContent toMessageContent(ContentBlock block) {
        if (block instanceof TextBlock text) {
            return new MessageContent.Text(text.getText() == null ? "" : text.getText());
        }
        if (block instanceof ImageBlock image
                && image.getSource() instanceof io.agentscope.core.message.URLSource url) {
            return new MessageContent.Image(url.getUrl(), null, url.getMimeType());
        }
        return new MessageContent.Text(JsonUtils.getJsonCodec().toJson(block));
    }

    /** 内容块的文本表示，用于流上的增量与大对象内容。 */
    static String textOf(MessageContent content) {
        return switch (content) {
            case MessageContent.Text text -> text.text();
            case MessageContent.Image image -> image.url() != null ? image.url() : image.fileId();
            case MessageContent.FileRef file -> file.fileId();
        };
    }

    private static <T extends Throwable> T causeOf(Throwable error, Class<T> type) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    /**
     * 一轮的映射参数。
     *
     * @param modelId 本轮主模型的平台模型 ID（{@code mdl_…}），用于 {@code span.model_request_*}；必填
     * @param maxIters 单轮最大推理次数，只用于日志
     * @param exposeThinking 是否在 {@code agent.thinking} 与流上带出思考内容
     * @param customToolNames 本会话声明的自定义工具名；它们以 {@code agent.custom_tool_use} 输出
     * @param approvedToolUseIds 本轮输入中被批准的工具调用 ID，对应结果的 {@code permission} 为 {@code approved}
     * @param platformStop 平台中间件记录的停止原因；必填
     */
    public record Settings(
            String modelId,
            int maxIters,
            boolean exposeThinking,
            Set<String> customToolNames,
            Set<String> approvedToolUseIds,
            PlatformStopRequest platformStop) {

        /**
         * 创建映射参数，对集合做不可变复制。
         *
         * @throws NullPointerException 缺少模型 ID 或停止记录时
         */
        public Settings {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(platformStop, "platformStop");
            customToolNames = customToolNames == null ? Set.of() : Set.copyOf(customToolNames);
            approvedToolUseIds = approvedToolUseIds == null ? Set.of() : Set.copyOf(approvedToolUseIds);
        }
    }

    /** 一个工具调用的名字与累积的入参 JSON 片段。 */
    private record ToolCallBuffer(String name, StringBuilder input) {
        ToolCallBuffer(String name) {
            this(name, new StringBuilder());
        }
    }

    /** 一个工具结果的名字与累积的内容，相邻文本合并为一块。 */
    private static final class ToolResultBuffer {
        private final String name;
        private final List<MessageContent> content = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        ToolResultBuffer(String name) {
            this.name = name;
        }

        String name() {
            return name;
        }

        void appendText(String delta) {
            if (delta != null) {
                text.append(delta);
            }
        }

        void append(MessageContent block) {
            if (block instanceof MessageContent.Text t) {
                text.append(t.text());
                return;
            }
            flushText();
            content.add(block);
        }

        List<MessageContent> content() {
            flushText();
            return List.copyOf(content);
        }

        private void flushText() {
            if (!text.isEmpty()) {
                content.add(new MessageContent.Text(text.toString()));
                text.setLength(0);
            }
        }
    }
}
