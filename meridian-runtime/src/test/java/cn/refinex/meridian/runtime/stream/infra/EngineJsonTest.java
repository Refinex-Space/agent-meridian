package cn.refinex.meridian.runtime.stream.infra;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.spi.engine.input.CustomToolResult;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.engine.input.ToolConfirmation;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
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
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** {@link EngineJson}：存储格式与对外 API 的 {@code data} 一致，且每种事件、每种输入都能原样读回。 */
class EngineJsonTest {

    @ParameterizedTest
    @EnumSource(EventType.class)
    void everyEventTypeRoundTrips(EventType type) {
        EngineEvent sample = sample(type);
        assertThat(sample.type()).isEqualTo(type);

        String json = EngineJson.writeEvent(sample);

        assertThat(EngineJson.readEvent(type, json)).isEqualTo(sample);
    }

    @Test
    void everyEnumReachableFromEventsAndInputsIsWrittenAsItsValue() throws Exception {
        Set<Class<?>> enums = new LinkedHashSet<>();
        Set<Type> visited = new LinkedHashSet<>();
        for (EventType type : EventType.values()) {
            collectEnums(type.eventClass(), enums, visited);
        }
        collectEnums(TurnInput.class, enums, visited);

        assertThat(enums)
                .contains(
                        StopReason.class,
                        SessionEvent.RescheduleReason.class,
                        PendingAction.Type.class,
                        PermissionBehavior.class,
                        RecoveryNote.Outcome.class);
        for (Class<?> enumType : enums) {
            Method value = valueMethod(enumType);
            for (Object constant : enumType.getEnumConstants()) {
                String expected = value != null
                        ? (String) value.invoke(constant)
                        : ((Enum<?>) constant).name().toLowerCase(Locale.ROOT);
                String json = EngineJson.mapper().writeValueAsString(constant);
                assertThat(json)
                        .as(constant.getClass().getName() + "." + constant)
                        .isEqualTo("\"" + expected + "\"");
                assertThat(EngineJson.mapper().readValue(json, enumType)).isEqualTo(constant);
            }
        }
    }

    @Test
    void dataIsSnakeCaseWithTypedContentBlocks() {
        String json = EngineJson.writeEvent(new AgentOutputEvent.ToolResult(
                "toolu_01",
                "knowledge_search",
                AgentOutputEvent.ToolResultState.SUCCESS,
                AgentOutputEvent.PermissionOutcome.ALLOWED,
                List.of(new MessageContent.Text("结果")),
                null));

        assertThat(json)
                .isEqualTo("{\"tool_use_id\":\"toolu_01\",\"name\":\"knowledge_search\",\"state\":\"success\","
                        + "\"permission\":\"allowed\",\"content\":[{\"type\":\"text\",\"text\":\"结果\"}],"
                        + "\"content_ref\":null}");
        assertThat(EngineJson.writeEvent(new SessionEvent.StatusRunning())).isEqualTo("{}");
        assertThat(EngineJson.writeEvent(new SessionEvent.StatusIdle(StopReason.END_TURN)))
                .isEqualTo("{\"stop_reason\":\"end_turn\"}");
    }

    @Test
    void everyTurnInputKindRoundTrips() {
        RecoveryNote note = new RecoveryNote(List.of(new RecoveryNote.RecoveredToolCall(
                "toolu_1", "write_file", RecoveryNote.Outcome.SUCCEEDED, "写入了 reports/a.md")));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("path", "a.md");
        input.put("note", null);
        List<TurnInput> inputs = List.of(
                new TurnInput.NewMessages(
                        List.of(new InboundMessage(
                                "evt_1",
                                List.of(
                                        new MessageContent.Text("看看这张图"),
                                        new MessageContent.Image(null, "fil_1", "image/png"),
                                        new MessageContent.FileRef("fil_2", "a.pdf", "application/pdf")))),
                        null),
                new TurnInput.Confirmations(
                        List.of(new ToolConfirmation(
                                "toolu_2",
                                "write_file",
                                input,
                                true,
                                null,
                                List.of(new PermissionRule(
                                        "write_file", "reports/**", PermissionBehavior.ALLOW, PolicyLayer.SESSION)))),
                        note),
                new TurnInput.CustomToolResults(
                        List.of(new CustomToolResult(
                                "toolu_3", "lookup_order", List.of(new MessageContent.Text("ok")), false)),
                        null),
                new TurnInput.RecoveryOnly(note));

        for (TurnInput turnInput : inputs) {
            String json = EngineJson.writeInput(turnInput);
            assertThat(EngineJson.readInput(turnInput.kind(), json)).isEqualTo(turnInput);
        }
        assertThat(EngineJson.writeInput(inputs.getFirst()))
                .contains("{\"type\":\"image\",\"url\":null,\"file_id\":\"fil_1\",\"media_type\":\"image/png\"}")
                .contains("{\"type\":\"file\",\"file_id\":\"fil_2\"");
    }

    @Test
    void storedDataWithUnknownFieldsStillReads() {
        assertThat(EngineJson.readEvent(
                        EventType.SESSION_STATUS_IDLE, "{\"stop_reason\":\"END_TURN\",\"added_later\":1}"))
                .isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
    }

    /** 每种事件类型一个样本；新增事件类型时 switch 不穷举会编译失败，从而补上样本。 */
    private static EngineEvent sample(EventType type) {
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

    private static void collectEnums(Type type, Set<Class<?>> enums, Set<Type> visited) {
        if (!visited.add(type)) {
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                collectEnums(argument, enums, visited);
            }
            return;
        }
        if (!(type instanceof Class<?> clazz) || !clazz.getPackageName().startsWith("cn.refinex.meridian")) {
            return;
        }
        if (clazz.isEnum()) {
            enums.add(clazz);
        } else if (clazz.isRecord()) {
            for (RecordComponent component : clazz.getRecordComponents()) {
                collectEnums(component.getGenericType(), enums, visited);
            }
        } else if (clazz.isSealed()) {
            for (Class<?> permitted : clazz.getPermittedSubclasses()) {
                collectEnums(permitted, enums, visited);
            }
        }
    }

    private static Method valueMethod(Class<?> enumType) {
        try {
            Method method = enumType.getMethod("value");
            return method.getReturnType() == String.class ? method : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
