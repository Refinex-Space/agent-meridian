package cn.refinex.meridian.engine.agentscope.turn;

import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.PROJECT;
import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.TIMEOUT;
import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.of;
import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.userText;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.agentscope.model.TokenEstimator;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.tool.RecordingTool;
import cn.refinex.meridian.engine.spi.engine.ThreadRef;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.engine.input.CustomToolResult;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.ToolConfirmation;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.error.BudgetExceededException;
import cn.refinex.meridian.engine.spi.error.RateLimitTimeoutException;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent.PermissionOutcome;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent.ToolResultState;
import cn.refinex.meridian.engine.spi.event.ContextComposition;
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
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * {@link AgentScopeEngine} 的场景测试：真实装配的 {@code HarnessAgent} 由 {@code ScriptedModel} 驱动，状态经
 * {@code FencedAgentStateStore} 写入内存端口（T04-A01、A03 至 A06、A08、A09）。
 */
class AgentScopeEngineScenarioTest {

    private static final PermissionPolicy OPEN = PermissionPolicy.empty();

    /** 非平凡的策略：{@code echo} 需要确认，其余工具放行。规则非空时 AgentScope 启用完整的权限引擎。 */
    private static final PermissionPolicy ASK_ECHO = new PermissionPolicy(
            PermissionMode.DEFAULT,
            List.of(
                    new PermissionRule("echo", null, PermissionBehavior.ASK, PolicyLayer.AGENT_VERSION),
                    new PermissionRule("lookup_order", null, PermissionBehavior.ALLOW, PolicyLayer.AGENT_VERSION)));

    @Test
    void plainTextIsStreamedAssembledAndEndsIdle() {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.text("你好，世界", 2).usage(42, 7))
                .build();
        EngineHarness harness = EngineHarness.create(model);
        TurnContext ctx = harness.claim("thr_text", "trn_1", 3, OPEN);

        List<EngineEvent> events = harness.run(ctx, userText("你好"));

        SpanEvent.ModelRequestStart start =
                of(events, SpanEvent.ModelRequestStart.class).getFirst();
        String replyId = start.requestId();
        String blockId = replyId + ":text";
        assertThat(start.context()).as("上下文构成：一条系统提示词、一条历史消息，其余为空").satisfies(context -> {
            assertThat(context.systemPrompt().count()).isEqualTo(1);
            assertThat(context.systemPrompt().tokens()).isPositive();
            assertThat(context.history().count()).isEqualTo(1);
            assertThat(context.history().tokens()).isGreaterThan(TokenEstimator.text("你好"));
            assertThat(context.skills()).isEqualTo(ContextComposition.Part.EMPTY);
            assertThat(context.toolResults()).isEqualTo(ContextComposition.Part.EMPTY);
            assertThat(context.injected()).isEqualTo(ContextComposition.Part.EMPTY);
        });
        assertThat(events)
                .containsExactly(
                        new SpanEvent.ModelRequestStart(replyId, "mdl_test", start.context()),
                        new StreamEvent.EventStart(blockId, BlockKind.TEXT, null),
                        new StreamEvent.EventDelta(blockId, BlockKind.TEXT, "你好"),
                        new StreamEvent.EventDelta(blockId, BlockKind.TEXT, "，世"),
                        new StreamEvent.EventDelta(blockId, BlockKind.TEXT, "界"),
                        new AgentOutputEvent.Message(blockId, List.of(new MessageContent.Text("你好，世界"))),
                        new SpanEvent.ModelRequestEnd(replyId, "mdl_test", TokenUsage.of(42, 7)),
                        new SessionEvent.StatusIdle(StopReason.END_TURN));
        assertThat(model.request(0).lastUserText()).isEqualTo("你好");
        assertThat(harness.states.contentEpoch(PROJECT + ":_", "thr_text"))
                .as("本轮的内容写入把 content_epoch 更新为当前 epoch")
                .hasValue(3);
    }

    @Test
    void identityAndPlatformContextFollowTheIdentityMapping() {
        ProbeTool probe = new ProbeTool();
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_p", "probe", Map.of("text", "x")))
                .respond(r -> r.text("好"))
                .build();
        EngineHarness harness = EngineHarness.create(model, probe);
        TurnContext ctx = harness.claim("thr_identity", "trn_1", 1, OPEN, "u_1024", List.of());

        harness.run(ctx, userText("看看上下文"));

        RuntimeContext runtime = probe.runtimeContext.get();
        assertThat(runtime.getSessionId()).as("sessionId 是线程 ID").isEqualTo("thr_identity");
        assertThat(runtime.getUserId()).isEqualTo(PROJECT + ":u_1024");
        assertThat(probe.fromRuntimeContext.get()).isEqualTo(ctx.platform());
        assertThat(probe.fromReactorContext.get()).as("Reactor Context 中也能取到").isEqualTo(ctx.platform());
        assertThat(harness.states.listThreadIds(PROJECT + ":u_1024"))
                .as("状态键按 (项目:终端用户, 线程) 存储")
                .containsExactly("thr_identity");
        assertThat(AgentScopeIdentity.userId(PROJECT, null)).as("没有终端用户").isEqualTo(PROJECT + ":_");
        assertThat(AgentScopeIdentity.userId(PROJECT, " ")).isEqualTo(PROJECT + ":_");
    }

    @Test
    void toolCallsAreAssembledExecutedAndReported() {
        RecordingTool echo = RecordingTool.echo("echo");
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", "echo", Map.of("text", "苹果")).usage(30, 5))
                .respond(r -> r.text("完成"))
                .build();
        EngineHarness harness = EngineHarness.create(model, echo);
        TurnContext ctx = harness.claim("thr_tool", "trn_1", 1, OPEN);

        List<EngineEvent> events = harness.run(ctx, userText("查一下苹果"));

        assertThat(of(events, AgentOutputEvent.ToolUse.class))
                .containsExactly(new AgentOutputEvent.ToolUse("call_1", "echo", Map.of("text", "苹果")));
        assertThat(of(events, AgentOutputEvent.ToolResult.class))
                .containsExactly(new AgentOutputEvent.ToolResult(
                        "call_1",
                        "echo",
                        ToolResultState.SUCCESS,
                        PermissionOutcome.ALLOWED,
                        List.of(new MessageContent.Text("echo:苹果")),
                        null));
        assertThat(events)
                .containsSubsequence(
                        new StreamEvent.EventStart("call_1", BlockKind.TOOL_USE, "echo"),
                        new AgentOutputEvent.ToolUse("call_1", "echo", Map.of("text", "苹果")),
                        new StreamEvent.EventStart("call_1", BlockKind.TOOL_RESULT, "echo"),
                        new StreamEvent.EventDelta("call_1", BlockKind.TOOL_RESULT, "echo:苹果"));
        assertThat(echo.calls()).hasSize(1);
        assertThat(events.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
    }

    @Test
    void interleavedParallelToolCallInputsDoNotMix() {
        RecordingTool echo = RecordingTool.echo("echo");
        RecordingTool upper = RecordingTool.responding(
                "upper", in -> String.valueOf(in.get("text")).toUpperCase());
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.rawChunk(new ToolUseBlock("call_a", "echo", Map.of(), "{\"text\":", null))
                        .rawChunk(new ToolUseBlock("call_b", "upper", Map.of(), "{\"text\":", null))
                        .rawChunk(new ToolUseBlock("call_a", "echo", Map.of(), "\"左\"}", null))
                        .rawChunk(new ToolUseBlock("call_b", "upper", Map.of(), "\"right\"}", null)))
                .respond(r -> r.text("都完成了"))
                .build();
        EngineHarness harness = EngineHarness.create(model, echo, upper);
        TurnContext ctx = harness.claim("thr_parallel", "trn_1", 1, OPEN);

        List<EngineEvent> events = harness.run(ctx, userText("并行"));

        assertThat(of(events, AgentOutputEvent.ToolUse.class))
                .containsExactly(
                        new AgentOutputEvent.ToolUse("call_a", "echo", Map.of("text", "左")),
                        new AgentOutputEvent.ToolUse("call_b", "upper", Map.of("text", "right")));
        assertThat(of(events, StreamEvent.EventStart.class).stream()
                        .filter(e -> e.kind() == BlockKind.TOOL_USE)
                        .map(StreamEvent.EventStart::blockId))
                .as("交错到达时每个调用只开始一次")
                .containsExactly("call_a", "call_b");
        Map<String, List<MessageContent>> results = of(events, AgentOutputEvent.ToolResult.class).stream()
                .collect(
                        Collectors.toMap(AgentOutputEvent.ToolResult::toolUseId, AgentOutputEvent.ToolResult::content));
        assertThat(results)
                .containsEntry("call_a", List.of(new MessageContent.Text("echo:左")))
                .containsEntry("call_b", List.of(new MessageContent.Text("RIGHT")));
        assertThat(echo.calls())
                .singleElement()
                .satisfies(c -> assertThat(c.getInput()).containsEntry("text", "左"));
        assertThat(upper.calls())
                .singleElement()
                .satisfies(c -> assertThat(c.getInput()).containsEntry("text", "right"));
    }

    @Test
    void askThenApproveResumesAndRunsTheApprovedCall() {
        RecordingTool echo = RecordingTool.echo("echo");
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", "echo", Map.of("text", "删库")))
                .respondTo((request, r) -> r.text("已执行：" + firstResultText(request.toolResults())))
                .build();
        EngineHarness harness = EngineHarness.create(model, echo);
        TurnContext first = harness.claim("thr_approve", "trn_1", 1, ASK_ECHO);

        List<EngineEvent> asking = harness.run(first, userText("执行"));

        assertThat(of(asking, AgentOutputEvent.ToolUse.class)).hasSize(1);
        SessionEvent.StatusRequiresAction action = (SessionEvent.StatusRequiresAction) asking.getLast();
        assertThat(action.actions()).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(PendingAction.Type.TOOL_CONFIRMATION);
            assertThat(a.toolUseId()).isEqualTo("call_1");
            assertThat(a.input()).containsEntry("text", "删库");
        });
        assertThat(echo.calls()).as("确认之前不执行").isEmpty();
        harness.release(first);

        TurnContext second = harness.claim("thr_approve", "trn_2", 2, ASK_ECHO);
        List<EngineEvent> resumed = harness.run(
                second,
                new TurnInput.Confirmations(
                        List.of(new ToolConfirmation("call_1", "echo", Map.of("text", "删库"), true, null, List.of())),
                        null));

        assertThat(echo.calls())
                .singleElement()
                .satisfies(c -> assertThat(c.getInput()).containsEntry("text", "删库"));
        assertThat(of(resumed, AgentOutputEvent.ToolResult.class))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.state()).isEqualTo(ToolResultState.SUCCESS);
                    assertThat(r.permission()).as("本轮有确认记录").isEqualTo(PermissionOutcome.APPROVED);
                });
        assertThat(of(resumed, AgentOutputEvent.Message.class))
                .singleElement()
                .satisfies(m -> assertThat(m.content()).containsExactly(new MessageContent.Text("已执行：echo:删库")));
        assertThat(resumed.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
        assertThat(model.remainingSteps()).isZero();
    }

    @Test
    void askThenDenyWritesTheDeniedResultAndContinues() {
        RecordingTool echo = RecordingTool.echo("echo");
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", "echo", Map.of("text", "删库")))
                .respondTo((request, r) -> r.text("被拒绝：" + firstResultText(request.toolResults())))
                .build();
        EngineHarness harness = EngineHarness.create(model, echo);
        TurnContext first = harness.claim("thr_deny", "trn_1", 1, ASK_ECHO);
        harness.run(first, userText("执行"));
        harness.release(first);

        TurnContext second = harness.claim("thr_deny", "trn_2", 2, ASK_ECHO);
        List<EngineEvent> resumed = harness.run(
                second,
                new TurnInput.Confirmations(
                        List.of(new ToolConfirmation(
                                "call_1", "echo", Map.of("text", "删库"), false, "不允许删库", List.of())),
                        null));

        assertThat(echo.calls()).as("拒绝的调用不执行").isEmpty();
        assertThat(of(resumed, AgentOutputEvent.ToolResult.class))
                .containsExactly(new AgentOutputEvent.ToolResult(
                        "call_1",
                        "echo",
                        ToolResultState.DENIED,
                        PermissionOutcome.DENIED,
                        List.of(new MessageContent.Text("不允许删库")),
                        null));
        assertThat(of(resumed, AgentOutputEvent.Message.class))
                .singleElement()
                .satisfies(m -> assertThat(m.content()).containsExactly(new MessageContent.Text("被拒绝：不允许删库")));
        assertThat(resumed.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
    }

    @Test
    void customToolSuspendsTheTurnAndResumesWithTheBusinessResult() {
        CustomToolDeclaration lookup = new CustomToolDeclaration(
                "lookup_order",
                "按订单号查询订单状态",
                Map.of("type", "object", "properties", Map.of("order_id", Map.of("type", "string"))),
                SideEffect.NONE);
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_c", "lookup_order", Map.of("order_id", "42")))
                .respondTo((request, r) -> r.text("订单状态：" + firstResultText(request.toolResults())))
                .build();
        EngineHarness harness = EngineHarness.create(model);
        TurnContext first = harness.claim("thr_custom", "trn_1", 1, OPEN, null, List.of(lookup));

        List<EngineEvent> suspended = harness.run(first, userText("订单 42 到哪了"));

        assertThat(model.request(0).toolNames())
                .as("自定义工具经 ToolRequestConfig 注入")
                .contains("lookup_order");
        assertThat(of(suspended, AgentOutputEvent.ToolUse.class))
                .as("自定义工具不以 agent.tool_use 输出")
                .isEmpty();
        assertThat(of(suspended, AgentOutputEvent.ToolResult.class))
                .as("挂起时不输出 running 结果")
                .isEmpty();
        assertThat(of(suspended, AgentOutputEvent.CustomToolUse.class))
                .containsExactly(
                        new AgentOutputEvent.CustomToolUse("call_c", "lookup_order", Map.of("order_id", "42")));
        assertThat(suspended.getLast())
                .isInstanceOfSatisfying(
                        SessionEvent.StatusRequiresAction.class,
                        s -> assertThat(s.actions()).singleElement().satisfies(a -> {
                            assertThat(a.type()).isEqualTo(PendingAction.Type.CUSTOM_TOOL_RESULT);
                            assertThat(a.toolUseId()).isEqualTo("call_c");
                        }));
        harness.release(first);

        TurnContext second = harness.claim("thr_custom", "trn_2", 2, OPEN, null, List.of(lookup));
        List<EngineEvent> resumed = harness.run(
                second,
                new TurnInput.CustomToolResults(
                        List.of(new CustomToolResult(
                                "call_c", "lookup_order", List.of(new MessageContent.Text("已发货")), false)),
                        null));

        assertThat(model.request(1).toolResults())
                .as("智能体收到业务方回传的结果")
                .singleElement()
                .satisfies(block -> assertThat(block.getId()).isEqualTo("call_c"));
        assertThat(of(resumed, AgentOutputEvent.Message.class))
                .singleElement()
                .satisfies(m -> assertThat(m.content()).containsExactly(new MessageContent.Text("订单状态：已发货")));
        assertThat(resumed.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
    }

    @Test
    void errorBeforeTheFirstChunkEndsWithSessionErrorThenIdle() {
        ScriptedModel model = ScriptedModel.builder()
                .failBeforeFirstChunk(new IllegalStateException("供应商不可用"))
                .build();
        EngineHarness harness = EngineHarness.create(model);
        TurnContext ctx = harness.claim("thr_fail_first", "trn_1", 1, OPEN);

        List<EngineEvent> events = harness.run(ctx, userText("你好"));

        assertThat(events.subList(events.size() - 2, events.size()))
                .containsExactly(
                        new SessionEvent.Error(CommonErrorCode.INTERNAL.code(), "本轮执行失败", RetryStatus.TERMINAL),
                        new SessionEvent.StatusIdle(StopReason.ERROR));
        assertThat(of(events, AgentOutputEvent.Message.class)).isEmpty();
    }

    @Test
    void errorAfterTheFirstChunkKeepsStreamedDeltasButNoMessage() {
        ScriptedModel model = ScriptedModel.builder()
                .failAfterChunks(r -> r.text("前半句", 1), new IllegalStateException("连接中断"))
                .build();
        EngineHarness harness = EngineHarness.create(model);
        TurnContext ctx = harness.claim("thr_fail_after", "trn_1", 1, OPEN);

        List<EngineEvent> events = harness.run(ctx, userText("你好"));

        assertThat(of(events, StreamEvent.EventDelta.class).stream().map(StreamEvent.EventDelta::delta))
                .containsExactly("前", "半", "句");
        assertThat(of(events, AgentOutputEvent.Message.class))
                .as("没有结束的块不输出完整消息")
                .isEmpty();
        assertThat(events.subList(events.size() - 2, events.size()))
                .containsExactly(
                        new SessionEvent.Error(CommonErrorCode.INTERNAL.code(), "本轮执行失败", RetryStatus.TERMINAL),
                        new SessionEvent.StatusIdle(StopReason.ERROR));
    }

    @Test
    void retryableErrorsRescheduleAndBudgetErrorsEndWithBudgetExceeded() {
        ScriptedModel model = ScriptedModel.builder()
                .failBeforeFirstChunk(new RateLimitTimeoutException("限流等待超过 30 秒"))
                .failBeforeFirstChunk(new BudgetExceededException("会话预算不足"))
                .build();
        EngineHarness harness = EngineHarness.create(model);

        List<EngineEvent> limited = harness.run(harness.claim("thr_limited", "trn_1", 1, OPEN), userText("a"));
        List<EngineEvent> budget = harness.run(harness.claim("thr_budget", "trn_2", 1, OPEN), userText("b"));

        assertThat(limited.subList(limited.size() - 2, limited.size()))
                .containsExactly(
                        new SessionEvent.Error("model.rate_limit_timeout", "限流等待超过 30 秒", RetryStatus.RETRYABLE),
                        new SessionEvent.StatusRescheduled(RescheduleReason.RETRYABLE_ERROR));
        assertThat(budget.subList(budget.size() - 2, budget.size()))
                .containsExactly(
                        new SessionEvent.Error("model.budget_exceeded", "会话预算不足", RetryStatus.TERMINAL),
                        new SessionEvent.StatusIdle(StopReason.BUDGET_EXCEEDED));
    }

    @Test
    void maxIterationsEndsWithMaxTurns() {
        RecordingTool echo = RecordingTool.echo("echo");
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", "echo", Map.of("text", "a")))
                .respond(r -> r.text("到上限了，先总结"))
                .build();
        EngineHarness harness = EngineHarness.create(model, spec -> spec.maxIters(1), List.of(), echo);
        TurnContext ctx = harness.claim("thr_max", "trn_1", 1, OPEN);

        List<EngineEvent> events = harness.run(ctx, userText("循环"));

        assertThat(events.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.MAX_TURNS));
    }

    @Test
    void userInterruptStopsAtTheNextCheckpointAndEndsInterrupted() throws Exception {
        LatchTool slow = new LatchTool();
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_s", "slow", Map.of("text", "x")))
                .respond(r -> r.text("不应被调用"))
                .build();
        EngineHarness harness = EngineHarness.create(model, slow);
        TurnContext ctx = harness.claim("thr_interrupt", "trn_1", 1, OPEN);
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        harness.stream(ctx, userText("慢慢来"))
                .doOnNext(events::add)
                .doFinally(s -> done.countDown())
                .subscribe();
        assertThat(slow.started.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(harness.engine.interrupt(new ThreadRef(EngineHarness.SESSION, "thr_interrupt")))
                .as("命中正在执行的调用")
                .isTrue();
        slow.release.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        EngineHarness.assertExactlyOneTerminalAtTheEnd(events);
        assertThat(events.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.INTERRUPTED));
        assertThat(model.requests()).as("正在执行的工具执行完毕，下一次推理前停止").hasSize(1);
        assertThat(harness.engine.interrupt(new ThreadRef(EngineHarness.SESSION, "thr_interrupt")))
                .as("执行结束后不再命中")
                .isFalse();
        assertThat(harness.engine.interrupt(new ThreadRef(EngineHarness.SESSION, "thr_unknown")))
                .isFalse();
    }

    @Test
    void cancellingTheSubscriptionEmitsNothingAndWritesNothing() throws Exception {
        LatchTool slow = new LatchTool();
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_s", "slow", Map.of("text", "x")))
                .respond(r -> r.text("不应被调用"))
                .respond(r -> r.text("下一轮"))
                .build();
        EngineHarness harness = EngineHarness.create(model, slow);
        TurnContext ctx = harness.claim("thr_cancel", "trn_1", 1, OPEN);
        List<EngineEvent> events = new CopyOnWriteArrayList<>();

        Disposable subscription = harness.stream(ctx, userText("慢慢来")).subscribe(events::add);
        assertThat(slow.started.await(10, TimeUnit.SECONDS)).isTrue();
        int eventsBeforeCancel = events.size();
        int writesBeforeCancel = harness.states.writeCount();
        subscription.dispose();
        slow.release.countDown();
        Thread.sleep(500);

        assertThat(events).as("取消后不再输出事件").hasSize(eventsBeforeCancel);
        assertThat(events).noneMatch(EngineHarness::isTerminal);
        assertThat(harness.states.writeCount()).as("取消后不再写状态").isEqualTo(writesBeforeCancel);
        assertThat(model.requests()).hasSize(1);

        // 取消也归还了实例并注销了执行：同一线程的下一次执行（新的 epoch）可以正常进行
        harness.release(ctx);
        List<EngineEvent> next = harness.run(harness.claim("thr_cancel", "trn_2", 2, OPEN), userText("继续"));
        assertThat(next.getLast()).isInstanceOf(SessionEvent.StatusIdle.class);
    }

    @Test
    void leaseGuardStopsBeforeTheNextToolExecutionWithoutEmittingATerminal() {
        RecordingTool echo = RecordingTool.echo("echo");
        AtomicReference<EngineHarness> holder = new AtomicReference<>();
        ScriptedModel model = ScriptedModel.builder()
                .respondTo((request, r) -> {
                    // 模型调用期间租约被其他节点以更高的 epoch 接管
                    holder.get().leases.grant("thr_guard_acting", "trn_other", 9);
                    r.toolCall("call_1", "echo", Map.of("text", "a"));
                })
                .build();
        EngineHarness harness = EngineHarness.create(model, echo);
        holder.set(harness);
        TurnContext ctx = harness.claim("thr_guard_acting", "trn_1", 1, OPEN);
        List<EngineEvent> events = new CopyOnWriteArrayList<>();

        StepVerifier.create(harness.stream(ctx, userText("执行")).doOnNext(events::add))
                .thenConsumeWhile(e -> true)
                .expectError(LeaseLostException.class)
                .verify(TIMEOUT);

        assertThat(echo.calls()).as("租约失效后不再执行工具").isEmpty();
        assertThat(events).as("租约丢失不输出终态").noneMatch(EngineHarness::isTerminal);
        assertThat(events).noneMatch(SessionEvent.Error.class::isInstance);
    }

    @Test
    void leaseGuardStopsBeforeTheNextReasoning() {
        AtomicReference<EngineHarness> holder = new AtomicReference<>();
        RecordingTool revoking = RecordingTool.responding("echo", in -> {
            holder.get().leases.revoke("thr_guard_reasoning");
            return "done";
        });
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", "echo", Map.of("text", "a")))
                .respond(r -> r.text("不应被调用"))
                .build();
        EngineHarness harness = EngineHarness.create(model, revoking);
        holder.set(harness);
        TurnContext ctx = harness.claim("thr_guard_reasoning", "trn_1", 1, OPEN);

        StepVerifier.create(harness.stream(ctx, userText("执行")))
                .thenConsumeWhile(e -> !EngineHarness.isTerminal(e))
                .expectError(LeaseLostException.class)
                .verify(TIMEOUT);

        assertThat(model.requests()).as("租约失效后不再发起推理").hasSize(1);
    }

    private static String firstResultText(List<ToolResultBlock> results) {
        return results.getFirst().getOutput().stream()
                .filter(io.agentscope.core.message.TextBlock.class::isInstance)
                .map(b -> ((io.agentscope.core.message.TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    /** 记录工具执行时看到的 {@code RuntimeContext} 与 Reactor Context 中的 {@code PlatformContext}。 */
    static final class ProbeTool extends ToolBase {

        final AtomicReference<RuntimeContext> runtimeContext = new AtomicReference<>();
        final AtomicReference<PlatformContext> fromRuntimeContext = new AtomicReference<>();
        final AtomicReference<PlatformContext> fromReactorContext = new AtomicReference<>();

        ProbeTool() {
            super(ToolBase.builder()
                    .name("probe")
                    .description("记录上下文的测试工具")
                    .inputSchema(RecordingTool.TEXT_SCHEMA)
                    .readOnly(true)
                    .concurrencySafe(true));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.deferContextual(view -> {
                runtimeContext.set(param.getRuntimeContext());
                fromRuntimeContext.set(param.getRuntimeContext().get(PlatformContext.class));
                fromReactorContext.set(PlatformContexts.find(view).orElse(null));
                return Mono.just(ToolResultBlock.text("ok"));
            });
        }
    }

    /** 执行时阻塞到测试放行的工具，用于在执行中途中断、取消或下线。 */
    static final class LatchTool extends ToolBase {

        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        LatchTool() {
            super(ToolBase.builder()
                    .name("slow")
                    .description("阻塞到测试放行的工具")
                    .inputSchema(RecordingTool.TEXT_SCHEMA)
                    .readOnly(true)
                    .concurrencySafe(true));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> {
                        started.countDown();
                        release.await(30, TimeUnit.SECONDS);
                        return ToolResultBlock.text("slow done");
                    })
                    .subscribeOn(Schedulers.boundedElastic());
        }
    }
}
