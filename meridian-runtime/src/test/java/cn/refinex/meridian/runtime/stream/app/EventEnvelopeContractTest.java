package cn.refinex.meridian.runtime.stream.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent;
import cn.refinex.meridian.engine.spi.event.BlobRef;
import cn.refinex.meridian.engine.spi.event.ContextComposition;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.SpanEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.StreamEvent;
import cn.refinex.meridian.engine.spi.event.TokenUsage;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import cn.refinex.meridian.runtime.support.ContractValidator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 事件信封与规范的一致（ADR-0027、ADR-0029）：每种事件按存储格式生成的信封，都符合 {@code meridian-v1.yaml} 中对应的 schema。
 * 持久化事件按 {@code SessionEvent}，只在流上的事件按 {@code StreamOnlyEvent}。
 */
class EventEnvelopeContractTest {

    private static final Instant AT = Instant.parse("2026-11-07T08:30:12.345Z");

    @ParameterizedTest
    @EnumSource(EventType.class)
    void everyEventEnvelopeConformsToTheSpec(EventType type) {
        EngineEvent event = sample(type);
        assertThat(event.type()).isEqualTo(type);
        String envelope = type.isPersisted()
                ? EventEnvelopes.json(new StoredEvent(
                        "ses_1", 7, "evt_1", "ten_1", "prj_1", "thr_1", null, type, EngineJson.writeEvent(event), AT))
                : EventEnvelopes.transientJson("ses_1", "thr_1", "trn_1", event, AT);

        ContractValidator.validateEvent(envelope, type.isPersisted());
    }

    @Test
    void nullableFieldsAndAlternativeShapesConformToo() {
        List<EngineEvent> variants = List.of(
                new AgentOutputEvent.Thinking("rpl_1:thinking", null),
                new SpanEvent.ModelRequestEnd("req_1", "mdl_1", null),
                new UserEvent.ToolConfirmation("act_1", true, null, null, UserEvent.RememberScope.NONE),
                new UserEvent.Message(
                        List.of(
                                new MessageContent.Image("https://example.com/a.png", null, null),
                                new MessageContent.FileRef("fil_1", null, null)),
                        UserEvent.Delivery.QUEUE),
                new SessionEvent.StatusRequiresAction(List.of(new PendingAction(
                        "act_1", PendingAction.Type.CUSTOM_TOOL_RESULT, "toolu_1", "lookup", Map.of(), null))));
        for (EngineEvent event : variants) {
            String envelope = EventEnvelopes.json(new StoredEvent(
                    "ses_1", 1, "evt_1", "ten_1", "prj_1", null, null, event.type(), EngineJson.writeEvent(event), AT));
            ContractValidator.validateEvent(envelope, true);
        }
    }

    @Test
    void anEnvelopeWithAnUndeclaredFieldIsRejected() {
        String envelope = EventEnvelopes.json(new StoredEvent(
                "ses_1",
                1,
                "evt_1",
                "ten_1",
                "prj_1",
                null,
                null,
                EventType.SESSION_STATUS_IDLE,
                "{\"stop_reason\":\"end_turn\",\"extra\":1}",
                AT));

        assertThatThrownBy(() -> ContractValidator.validateEvent(envelope, true))
                .isInstanceOf(AssertionError.class);
    }

    /** 每种事件类型一个样本，覆盖可空字段的两种取值；新增事件类型时 switch 不穷举会编译失败。 */
    static EngineEvent sample(EventType type) {
        Map<String, Object> input = Map.of("query", "差旅标准");
        List<MessageContent> content = List.of(new MessageContent.Text("你好"));
        TokenUsage usage = new TokenUsage(120, 30, 64, 0, 8);
        return switch (type) {
            case USER_MESSAGE -> new UserEvent.Message(content, UserEvent.Delivery.STEER);
            case USER_INTERRUPT -> new UserEvent.Interrupt();
            case USER_TOOL_CONFIRMATION ->
                new UserEvent.ToolConfirmation("act_1", false, input, "不允许", UserEvent.RememberScope.SESSION);
            case USER_CUSTOM_TOOL_RESULT -> new UserEvent.CustomToolResult("act_2", content, true);
            case USER_RECOVERY_CONFIRMATION ->
                new UserEvent.RecoveryConfirmation("act_3", UserEvent.RecoveryDecision.RETRY);
            case AGENT_MESSAGE -> new AgentOutputEvent.Message("rpl_1:text", content);
            case AGENT_THINKING -> new AgentOutputEvent.Thinking("rpl_1:thinking", "想一想");
            case AGENT_TOOL_USE -> new AgentOutputEvent.ToolUse("toolu_1", "knowledge_search", input);
            case AGENT_TOOL_RESULT ->
                new AgentOutputEvent.ToolResult(
                        "toolu_1",
                        "knowledge_search",
                        AgentOutputEvent.ToolResultState.DENIED,
                        AgentOutputEvent.PermissionOutcome.DENIED,
                        content,
                        new BlobRef("rt/blobs/1", "application/json", 20_000));
            case AGENT_MCP_TOOL_USE -> new AgentOutputEvent.McpToolUse("toolu_2", "mcp__crm__find", "crm", input);
            case AGENT_MCP_TOOL_RESULT ->
                new AgentOutputEvent.McpToolResult(
                        "toolu_2",
                        "mcp__crm__find",
                        "crm",
                        AgentOutputEvent.ToolResultState.SUCCESS,
                        AgentOutputEvent.PermissionOutcome.APPROVED,
                        content,
                        null);
            case AGENT_CUSTOM_TOOL_USE -> new AgentOutputEvent.CustomToolUse("toolu_3", "lookup_order", input);
            case AGENT_CONTEXT_COMPACTED -> new AgentOutputEvent.ContextCompacted(40, 6);
            case SESSION_STATUS_RUNNING -> new SessionEvent.StatusRunning();
            case SESSION_STATUS_IDLE -> new SessionEvent.StatusIdle(StopReason.MAX_TURNS);
            case SESSION_STATUS_REQUIRES_ACTION ->
                new SessionEvent.StatusRequiresAction(List.of(new PendingAction(
                        "act_1",
                        PendingAction.Type.TOOL_CONFIRMATION,
                        "toolu_1",
                        "write_file",
                        input,
                        Instant.parse("2026-11-07T08:30:12.345Z"))));
            case SESSION_STATUS_RESCHEDULED ->
                new SessionEvent.StatusRescheduled(SessionEvent.RescheduleReason.RETRYABLE_ERROR);
            case SESSION_STATUS_TERMINATED -> new SessionEvent.StatusTerminated(SessionEvent.TerminationReason.DELETED);
            case SESSION_ERROR ->
                new SessionEvent.Error("model.provider_unavailable", "供应商不可用", SessionEvent.RetryStatus.RETRYABLE);
            case SESSION_USAGE ->
                new SessionEvent.Usage(
                        "mdl_1",
                        usage,
                        usage.plus(usage),
                        List.of(new SessionEvent.Usage.ModelUsage("mdl_1", 2, usage)));
            case SESSION_MESSAGE_CONSUMED -> new SessionEvent.MessageConsumed(List.of("evt_1", "evt_2"));
            case SESSION_THREAD_CREATED -> new SessionEvent.ThreadCreated("thr_2", "thr_1", "toolu_9", "researcher");
            case SESSION_THREAD_STATUS_RUNNING -> new SessionEvent.ThreadStatusRunning("thr_2");
            case SESSION_THREAD_STATUS_IDLE -> new SessionEvent.ThreadStatusIdle("thr_2", StopReason.END_TURN);
            case SPAN_MODEL_REQUEST_START ->
                new SpanEvent.ModelRequestStart(
                        "req_1",
                        "mdl_1",
                        new ContextComposition(
                                new ContextComposition.Part(1, 320),
                                new ContextComposition.Part(2, 140),
                                new ContextComposition.Part(6, 1_800),
                                new ContextComposition.Part(2, 600),
                                ContextComposition.Part.EMPTY));
            case SPAN_MODEL_REQUEST_END -> new SpanEvent.ModelRequestEnd("req_1", "mdl_1", usage);
            case EVENT_START -> new StreamEvent.EventStart("rpl_1:text", StreamEvent.BlockKind.TEXT, null);
            case EVENT_DELTA -> new StreamEvent.EventDelta("rpl_1:text", StreamEvent.BlockKind.TOOL_USE, "{\"q\"");
            case PING -> new StreamEvent.Ping();
        };
    }
}
