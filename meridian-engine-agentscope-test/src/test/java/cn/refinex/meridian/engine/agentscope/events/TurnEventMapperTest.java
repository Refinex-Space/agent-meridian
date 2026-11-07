package cn.refinex.meridian.engine.agentscope.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformStopRequest;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryBlobStore;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.error.BudgetExceededException;
import cn.refinex.meridian.engine.spi.error.RateLimitTimeoutException;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent.PermissionOutcome;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent.ToolResultState;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent.RescheduleReason;
import cn.refinex.meridian.engine.spi.event.SessionEvent.RetryStatus;
import cn.refinex.meridian.engine.spi.event.SpanEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.StreamEvent;
import cn.refinex.meridian.engine.spi.event.StreamEvent.BlockKind;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
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
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * {@link TurnEventMapper} 的单元测试：用手工构造的 AgentScope 事件覆盖增量拼装（含交错到达的并行工具调用）、
 * 结果的权限推导与大对象、待处理动作、停止原因与错误映射（T04-A02、A03、A04 的映射部分）。
 */
class TurnEventMapperTest {

    private static final String MODEL = "mdl_test";

    private final InMemoryBlobStore blobs = new InMemoryBlobStore();
    private final PlatformStopRequest stop = new PlatformStopRequest();

    @Test
    void textBlocksAreAssembledPerReplySoBlockIdsOfSuccessiveModelCallsDoNotCollide() {
        TurnEventMapper mapper = mapper(Set.of(), Set.of());

        List<EngineEvent> events = map(
                mapper,
                new ModelCallStartEvent("r1"),
                new TextBlockStartEvent("r1", "text"),
                new TextBlockDeltaEvent("r1", "text", "你"),
                new TextBlockDeltaEvent("r1", "text", "好"),
                new TextBlockEndEvent("r1", "text"),
                new ModelCallEndEvent(
                        "r1",
                        ChatUsage.builder().inputTokens(10).outputTokens(2).build()),
                new ModelCallStartEvent("r2"),
                new TextBlockStartEvent("r2", "text"),
                new TextBlockDeltaEvent("r2", "text", "再见"),
                new TextBlockEndEvent("r2", "text"),
                new ModelCallEndEvent("r2", null));

        assertThat(events.getFirst()).isEqualTo(new SpanEvent.ModelRequestStart("r1", MODEL));
        assertThat(events)
                .filteredOn(AgentOutputEvent.Message.class::isInstance)
                .containsExactly(
                        new AgentOutputEvent.Message("r1:text", List.of(new MessageContent.Text("你好"))),
                        new AgentOutputEvent.Message("r2:text", List.of(new MessageContent.Text("再见"))));
        assertThat(events)
                .filteredOn(StreamEvent.EventDelta.class::isInstance)
                .containsExactly(
                        new StreamEvent.EventDelta("r1:text", BlockKind.TEXT, "你"),
                        new StreamEvent.EventDelta("r1:text", BlockKind.TEXT, "好"),
                        new StreamEvent.EventDelta("r2:text", BlockKind.TEXT, "再见"));
        assertThat(events)
                .filteredOn(SpanEvent.ModelRequestEnd.class::isInstance)
                .extracting(e -> ((SpanEvent.ModelRequestEnd) e).usage())
                .containsExactly(new cn.refinex.meridian.engine.spi.event.TokenUsage(10, 2, 0, 0, 0), null);
    }

    @Test
    void interleavedParallelToolCallInputsAreAssembledPerCallId() {
        TurnEventMapper mapper = mapper(Set.of(), Set.of());

        // 2.0.4 的形状：新的调用 ID 到达时先结束此前所有调用，同一 ID 再次到达时重新 START
        List<EngineEvent> events = map(
                mapper,
                new ModelCallStartEvent("r1"),
                new ToolCallStartEvent("r1", "call_a", "echo"),
                new ToolCallDeltaEvent("r1", "call_a", "echo", "{\"text\":"),
                new ToolCallEndEvent("r1", "call_a", "echo"),
                new ToolCallStartEvent("r1", "call_b", "mcp__crm__query"),
                new ToolCallDeltaEvent("r1", "call_b", "mcp__crm__query", "{\"sql\":"),
                new ToolCallEndEvent("r1", "call_b", "mcp__crm__query"),
                new ToolCallStartEvent("r1", "call_a", "echo"),
                new ToolCallDeltaEvent("r1", "call_a", "echo", "\"a\"}"),
                new ToolCallEndEvent("r1", "call_a", "echo"),
                new ToolCallStartEvent("r1", "call_b", "mcp__crm__query"),
                new ToolCallDeltaEvent("r1", "call_b", "mcp__crm__query", "\"select 1\"}"),
                new ToolCallEndEvent("r1", "call_b", "mcp__crm__query"),
                new ModelCallEndEvent("r1", null));

        assertThat(events)
                .filteredOn(StreamEvent.EventStart.class::isInstance)
                .as("每个调用在流上只开始一次")
                .containsExactly(
                        new StreamEvent.EventStart("call_a", BlockKind.TOOL_USE, "echo"),
                        new StreamEvent.EventStart("call_b", BlockKind.TOOL_USE, "mcp__crm__query"));
        assertThat(events)
                .filteredOn(e -> e instanceof AgentOutputEvent.ToolUse || e instanceof AgentOutputEvent.McpToolUse)
                .containsExactly(
                        new AgentOutputEvent.ToolUse("call_a", "echo", Map.of("text", "a")),
                        new AgentOutputEvent.McpToolUse("call_b", "mcp__crm__query", "crm", Map.of("sql", "select 1")));
        assertThat(events.getLast()).isInstanceOf(SpanEvent.ModelRequestEnd.class);
    }

    @Test
    void toolResultPermissionIsDerivedFromStateAndConfirmations() {
        TurnEventMapper mapper = mapper(Set.of(), Set.of("call_approved"));

        List<EngineEvent> events = map(
                mapper,
                new ToolResultStartEvent("r1", "call_plain", "echo"),
                new ToolResultTextDeltaEvent("r1", "call_plain", "echo", "echo:"),
                new ToolResultTextDeltaEvent("r1", "call_plain", "echo", "a"),
                new ToolResultEndEvent("r1", "call_plain", "echo", io.agentscope.core.message.ToolResultState.SUCCESS),
                new ToolResultStartEvent("r1", "call_approved", "echo"),
                new ToolResultEndEvent(
                        "r1", "call_approved", "echo", io.agentscope.core.message.ToolResultState.SUCCESS),
                new ToolResultStartEvent("r1", "call_denied", "echo"),
                new ToolResultTextDeltaEvent("r1", "call_denied", "echo", "拒绝"),
                new ToolResultEndEvent("r1", "call_denied", "echo", io.agentscope.core.message.ToolResultState.DENIED));

        assertThat(events)
                .filteredOn(AgentOutputEvent.ToolResult.class::isInstance)
                .containsExactly(
                        new AgentOutputEvent.ToolResult(
                                "call_plain",
                                "echo",
                                ToolResultState.SUCCESS,
                                PermissionOutcome.ALLOWED,
                                List.of(new MessageContent.Text("echo:a")),
                                null),
                        new AgentOutputEvent.ToolResult(
                                "call_approved",
                                "echo",
                                ToolResultState.SUCCESS,
                                PermissionOutcome.APPROVED,
                                List.of(),
                                null),
                        new AgentOutputEvent.ToolResult(
                                "call_denied",
                                "echo",
                                ToolResultState.DENIED,
                                PermissionOutcome.DENIED,
                                List.of(new MessageContent.Text("拒绝")),
                                null));
    }

    @Test
    void resultsLargerThan16KbAreStoredAsBlobs() {
        TurnEventMapper mapper = mapper(Set.of(), Set.of());
        String big = "x".repeat(BlobStorePort.INLINE_LIMIT_BYTES + 1);
        String atLimit = "y".repeat(BlobStorePort.INLINE_LIMIT_BYTES);

        List<EngineEvent> events = map(
                mapper,
                new ToolResultStartEvent("r1", "call_big", "echo"),
                new ToolResultTextDeltaEvent("r1", "call_big", "echo", big),
                new ToolResultEndEvent("r1", "call_big", "echo", io.agentscope.core.message.ToolResultState.SUCCESS),
                new ToolResultStartEvent("r1", "call_limit", "echo"),
                new ToolResultTextDeltaEvent("r1", "call_limit", "echo", atLimit),
                new ToolResultEndEvent("r1", "call_limit", "echo", io.agentscope.core.message.ToolResultState.SUCCESS));

        List<AgentOutputEvent.ToolResult> results = events.stream()
                .filter(AgentOutputEvent.ToolResult.class::isInstance)
                .map(AgentOutputEvent.ToolResult.class::cast)
                .toList();
        assertThat(results.get(0).content()).isEmpty();
        assertThat(results.get(0).contentRef()).isNotNull();
        assertThat(results.get(0).contentRef().size()).isEqualTo(big.length());
        assertThat(new String(blobs.get(results.get(0).contentRef()).block())).isEqualTo(big);
        assertThat(results.get(1).contentRef()).as("恰好 16 KB 仍内联").isNull();
        assertThat(blobs.size()).isEqualTo(1);
    }

    @Test
    void customToolsAreAnnouncedWhenSuspendedAndEndTheTurnWithAnAction() {
        TurnEventMapper mapper = mapper(Set.of("lookup_order"), Set.of());
        ToolUseBlock call = new ToolUseBlock("call_c", "lookup_order", Map.of("order_id", "42"));

        List<EngineEvent> events = map(
                mapper,
                new ModelCallStartEvent("r1"),
                new ToolCallStartEvent("r1", "call_c", "lookup_order"),
                new ToolCallDeltaEvent("r1", "call_c", "lookup_order", "{\"order_id\":\"42\"}"),
                new ToolCallEndEvent("r1", "call_c", "lookup_order"),
                new ModelCallEndEvent("r1", null),
                new ToolResultStartEvent("r2", "call_c", "lookup_order"),
                new ToolResultEndEvent(
                        "r2", "call_c", "lookup_order", io.agentscope.core.message.ToolResultState.RUNNING),
                new RequireExternalExecutionEvent("r2", List.of(call)),
                result(GenerateReason.TOOL_SUSPENDED));
        events.add(mapper.complete());

        assertThat(events)
                .noneMatch(e -> e instanceof AgentOutputEvent.ToolUse || e instanceof AgentOutputEvent.ToolResult);
        assertThat(events)
                .filteredOn(AgentOutputEvent.CustomToolUse.class::isInstance)
                .containsExactly(
                        new AgentOutputEvent.CustomToolUse("call_c", "lookup_order", Map.of("order_id", "42")));
        assertThat(events.getLast())
                .isInstanceOfSatisfying(
                        SessionEvent.StatusRequiresAction.class,
                        s -> assertThat(s.actions()).singleElement().satisfies(a -> {
                            assertThat(a.id()).startsWith("act_");
                            assertThat(a.type()).isEqualTo(PendingAction.Type.CUSTOM_TOOL_RESULT);
                            assertThat(a.toolUseId()).isEqualTo("call_c");
                            assertThat(a.input()).containsEntry("order_id", "42");
                            assertThat(a.expiresAt()).isNull();
                        }));
    }

    @Test
    void permissionAskingEndsWithConfirmationActionsIncludingPlanExit() {
        TurnEventMapper mapper = mapper(Set.of(), Set.of());

        map(
                mapper,
                new RequireUserConfirmEvent(
                        "r1",
                        List.of(
                                new ToolUseBlock("call_1", "echo", Map.of("text", "a")),
                                new ToolUseBlock("call_2", "plan_exit", Map.of()))),
                result(GenerateReason.PERMISSION_ASKING));

        assertThat(mapper.complete())
                .isInstanceOfSatisfying(
                        SessionEvent.StatusRequiresAction.class,
                        s -> assertThat(s.actions())
                                .extracting(PendingAction::type, PendingAction::toolUseId)
                                .containsExactly(
                                        org.assertj.core.groups.Tuple.tuple(
                                                PendingAction.Type.TOOL_CONFIRMATION, "call_1"),
                                        org.assertj.core.groups.Tuple.tuple(PendingAction.Type.PLAN_EXIT, "call_2")));
    }

    @Test
    void thinkingContentIsWithheldWhenNotExposed() {
        TurnEventMapper hidden = new TurnEventMapper(settings(false, Set.of(), Set.of()), blobs);

        List<EngineEvent> events = map(
                hidden,
                new ThinkingBlockStartEvent("r1", "thinking"),
                new ThinkingBlockDeltaEvent("r1", "thinking", "秘密"),
                new ThinkingBlockEndEvent("r1", "thinking"));

        assertThat(events)
                .containsExactly(
                        new StreamEvent.EventStart("r1:thinking", BlockKind.THINKING, null),
                        new AgentOutputEvent.Thinking("r1:thinking", null));
    }

    @Test
    void subagentEventsAndAgentStartAreNotMapped() {
        TurnEventMapper mapper = mapper(Set.of(), Set.of());

        List<EngineEvent> events = map(
                mapper,
                new AgentStartEvent(null, "r0", "main"),
                new TextBlockStartEvent("r1", "text").withSource("main/child"),
                new TextBlockDeltaEvent("r1", "text", "子线程").withSource("main/child"));

        assertThat(events).isEmpty();
    }

    @Test
    void completionMapsTheGenerateReasonToExactlyOneTerminal() {
        assertThat(terminal(GenerateReason.MODEL_STOP)).isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
        assertThat(terminal(GenerateReason.INTERRUPTED)).isEqualTo(new SessionEvent.StatusIdle(StopReason.INTERRUPTED));
        assertThat(terminal(GenerateReason.MAX_ITERATIONS))
                .isEqualTo(new SessionEvent.StatusIdle(StopReason.MAX_TURNS));
        assertThat(terminal(GenerateReason.TOOL_CALLS)).isEqualTo(new SessionEvent.StatusIdle(StopReason.ERROR));
        assertThat(terminal(null)).as("没有调用结果").isEqualTo(new SessionEvent.StatusIdle(StopReason.ERROR));
        assertThat(terminal(GenerateReason.PERMISSION_ASKING))
                .as("没有待处理动作的 requires_action 按 error 结束")
                .isEqualTo(new SessionEvent.StatusIdle(StopReason.ERROR));
        assertThat(terminal(GenerateReason.MIDDLEWARE_STOP_REQUESTED))
                .as("非平台中间件请求停止")
                .isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
    }

    @Test
    void platformStopRequestsCarryTheirRecordedReason() {
        RuntimeContext ctx =
                RuntimeContext.builder().put(PlatformStopRequest.class, stop).build();
        PlatformStopRequest.request(ctx, StopReason.TOOLS_DENIED);
        PlatformStopRequest.request(ctx, StopReason.MAX_TURNS);

        assertThat(terminal(GenerateReason.ACTING_STOP_REQUESTED))
                .as("第一次请求生效")
                .isEqualTo(new SessionEvent.StatusIdle(StopReason.TOOLS_DENIED));
    }

    @Test
    void errorsAreMappedToSessionErrorThenOneTerminal() {
        TurnEventMapper mapper = mapper(Set.of(), Set.of());

        assertThat(mapper.fail(new RuntimeException("包装", new BudgetExceededException("会话预算不足")))
                        .collectList()
                        .block())
                .containsExactly(
                        new SessionEvent.Error("model.budget_exceeded", "会话预算不足", RetryStatus.TERMINAL),
                        new SessionEvent.StatusIdle(StopReason.BUDGET_EXCEEDED));
        assertThat(mapper.fail(new RateLimitTimeoutException("等待超时"))
                        .collectList()
                        .block())
                .containsExactly(
                        new SessionEvent.Error("model.rate_limit_timeout", "等待超时", RetryStatus.RETRYABLE),
                        new SessionEvent.StatusRescheduled(RescheduleReason.RETRYABLE_ERROR));
        assertThat(mapper.fail(new IllegalStateException("供应商不可用：内部主机 10.0.0.1"))
                        .collectList()
                        .block())
                .as("未预期的错误不暴露内部信息")
                .containsExactly(
                        new SessionEvent.Error(
                                CommonErrorCode.INTERNAL.code(),
                                TurnEventMapper.INTERNAL_ERROR_MESSAGE,
                                RetryStatus.TERMINAL),
                        new SessionEvent.StatusIdle(StopReason.ERROR));
        assertThat(mapper.fail(new AgentShuttingDownException()).collectList().block())
                .as("节点下线不输出 session.error")
                .containsExactly(new SessionEvent.StatusRescheduled(RescheduleReason.NODE_SHUTDOWN));
    }

    @Test
    void lostLeaseProducesNoEventsAndKeepsTheError() {
        TurnEventMapper mapper = mapper(Set.of(), Set.of());
        LeaseLostException lost = new LeaseLostException("epoch 3 已失效");

        assertThatThrownBy(() -> mapper.fail(lost).collectList().block()).isSameAs(lost);
    }

    private TurnEventMapper mapper(Set<String> customTools, Set<String> approved) {
        return new TurnEventMapper(settings(true, customTools, approved), blobs);
    }

    private TurnEventMapper.Settings settings(boolean exposeThinking, Set<String> customTools, Set<String> approved) {
        return new TurnEventMapper.Settings(MODEL, 50, exposeThinking, customTools, approved, stop);
    }

    private EngineEvent terminal(GenerateReason reason) {
        TurnEventMapper mapper = mapper(Set.of(), Set.of());
        if (reason != null) {
            map(mapper, result(reason));
        }
        return mapper.complete();
    }

    private static AgentResultEvent result(GenerateReason reason) {
        return new AgentResultEvent(AssistantMessage.builder()
                .textContent("")
                .generateReason(reason)
                .build());
    }

    private static List<EngineEvent> map(TurnEventMapper mapper, AgentEvent... events) {
        List<EngineEvent> out = new ArrayList<>(
                Flux.fromArray(events).concatMap(mapper::map).collectList().block());
        return out;
    }
}
