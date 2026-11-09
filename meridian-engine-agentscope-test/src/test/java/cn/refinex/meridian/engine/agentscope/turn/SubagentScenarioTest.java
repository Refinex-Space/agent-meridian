package cn.refinex.meridian.engine.agentscope.turn;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.engine.agentscope.middleware.BatchCheckpoint;
import cn.refinex.meridian.engine.agentscope.middleware.SubagentQuota;
import cn.refinex.meridian.engine.agentscope.testkit.model.RecordedRequest;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedResponse;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryThreadRegistry;
import cn.refinex.meridian.engine.agentscope.testkit.sandbox.FakeSandbox;
import cn.refinex.meridian.engine.agentscope.testkit.tool.RecordingTool;
import cn.refinex.meridian.engine.agentscope.tools.PlatformTool;
import cn.refinex.meridian.engine.agentscope.tools.RuleSyntax;
import cn.refinex.meridian.engine.agentscope.tools.ToolCall;
import cn.refinex.meridian.engine.agentscope.tools.ToolOutput;
import cn.refinex.meridian.engine.spi.engine.ThreadRef;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.engine.spi.spec.SkillRef;
import cn.refinex.meridian.engine.spi.spec.SubagentSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * {@code spawn_subagent} 的引擎场景（T13，架构设计「子智能体」）：生产的平台工具、主智能体与子智能体各自的中间件、两个不同的
 * {@code ScriptedModel}、内存的线程登记与事件端口。
 */
class SubagentScenarioTest {

    private static final String MAIN_THREAD = "thr_main";
    private static final String TURN = "trn_main";
    private static final String CHILD_MODEL = "mdl_child";
    /** 测试替身占用的平台工具名：子智能体的工具必须是父智能体工具集内的平台工具名。 */
    private static final String PROBE = PlatformToolNames.WEB_SEARCH;

    private static final PermissionPolicy PARENT_ALLOWS = new PermissionPolicy(
            PermissionMode.DEFAULT,
            List.of(allow(PlatformToolNames.SPAWN_SUBAGENT), allow(PROBE), allow(PlatformToolNames.BASH)));

    /** runtime 为子线程预先合成的策略形态：DONT_ASK，只有父智能体放行的子集。 */
    private static final PermissionPolicy CHILD_ALLOWS =
            new PermissionPolicy(PermissionMode.DONT_ASK, List.of(allow(PROBE), allow(PlatformToolNames.BASH)));

    @Test
    void parentSpawnsSubagentAndContinuesWithItsAnswer() {
        ScriptedModel parent = ScriptedModel.builder()
                .modelName("parent-model")
                .respond(r -> r.toolCall(
                        "call_spawn", PlatformToolNames.SPAWN_SUBAGENT, spawn("researcher", "look up order o-1")))
                .respondTo((request, r) -> r.text("parent saw: " + lastToolResult(request)))
                .build();
        ScriptedModel child = ScriptedModel.builder()
                .modelName("child-model")
                .respond(r -> r.toolCall("call_probe", PROBE, Map.of("text", "o-1")))
                .respondTo((request, r) -> r.text("order o-1 shipped (" + lastToolResult(request) + ")"))
                .build();
        Probe probe = new Probe();
        EngineHarness harness = harness(parent, child, probe, 8, subagent("researcher", Duration.ofSeconds(30)));

        List<EngineEvent> events = harness.run(claim(harness), EngineHarness.userText("where is o-1?"));

        assertThat(events.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
        AgentOutputEvent.ToolResult spawnResult = toolResult(events, "call_spawn");
        assertThat(spawnResult.state()).isEqualTo(AgentOutputEvent.ToolResultState.SUCCESS);
        assertThat(text(spawnResult)).isEqualTo("order o-1 shipped (probe:o-1)");
        assertThat(lastToolResult(parent.request(1))).isEqualTo("order o-1 shipped (probe:o-1)");
        assertThat(EngineHarness.of(events, AgentOutputEvent.Message.class)
                        .getLast()
                        .content()
                        .toString())
                .contains("parent saw: order o-1 shipped");

        InMemoryThreadRegistry.ChildThread thread = harness.threads.threads().getFirst();
        assertThat(harness.threads.threads()).hasSize(1);
        assertThat(thread.parentThreadId()).isEqualTo(MAIN_THREAD);
        assertThat(thread.parentToolUseId()).isEqualTo("call_spawn");
        assertThat(thread.agentName()).isEqualTo("researcher");

        List<EngineEvent> childEvents = harness.events.eventsOf(thread.threadId());
        assertThat(childEvents.getFirst())
                .isEqualTo(new SessionEvent.ThreadCreated(thread.threadId(), MAIN_THREAD, "call_spawn", "researcher"));
        assertThat(childEvents.get(1)).isEqualTo(new SessionEvent.ThreadStatusRunning(thread.threadId()));
        assertThat(childEvents.getLast())
                .isEqualTo(new SessionEvent.ThreadStatusIdle(thread.threadId(), StopReason.END_TURN));
        assertThat(childEvents)
                .anySatisfy(e -> assertThat(e)
                        .isInstanceOfSatisfying(
                                AgentOutputEvent.ToolUse.class,
                                use -> assertThat(use.name()).isEqualTo(PROBE)));
        assertThat(childEvents)
                .as("子线程的终态不写成会话状态事件")
                .noneMatch(e -> e instanceof SessionEvent.StatusIdle || e instanceof SessionEvent.StatusRunning);
        assertThat(harness.events.events())
                .as("内核直接写入的事件都属于子线程")
                .allMatch(e -> e.threadId().equals(thread.threadId()));

        // 模型选择：父智能体与子智能体各用自己的模型，子智能体只看到自己的工具与提示词
        assertThat(parent.requests()).hasSize(2);
        assertThat(child.requests()).hasSize(2);
        assertThat(parent.request(0).toolNames()).contains(PlatformToolNames.SPAWN_SUBAGENT, PROBE);
        assertThat(child.request(0).toolNames()).contains(PROBE).doesNotContain(PlatformToolNames.SPAWN_SUBAGENT);
        assertThat(child.request(0).systemPrompt()).contains("You are the researcher subagent.");
        assertThat(child.request(0).lastUserText()).isEqualTo("look up order o-1");
        assertThat(child.request(0).messages().toString()).doesNotContain("where is o-1?");

        // 子线程的工具调用记在子线程上，状态以父轮次的租约写入，执行结束后解除子线程的租约登记
        assertThat(harness.ledger.invocations())
                .filteredOn(i -> i.start().toolName().equals(PROBE))
                .singleElement()
                .satisfies(i -> assertThat(i.start().threadId()).isEqualTo(thread.threadId()));
        assertThat(harness.ledger.invocations())
                .filteredOn(i -> i.start().toolName().equals(PlatformToolNames.SPAWN_SUBAGENT))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.start().threadId()).isEqualTo(MAIN_THREAD);
                    assertThat(i.start().sideEffect()).as("取子智能体工具中最高的等级").isEqualTo(SideEffect.IDEMPOTENT);
                    assertThat(i.status()).isEqualTo(ToolLedgerPort.Status.SUCCEEDED);
                });
        assertThat(harness.states.jsonOf(userId(), thread.threadId())).contains("order o-1 shipped");
        assertThat(harness.leases.find(thread.threadId())).isEmpty();
        assertThat(probe.contexts).singleElement().satisfies(captured -> {
            PlatformContext platform = captured.get(PlatformContext.class);
            assertThat(platform.threadId()).isEqualTo(thread.threadId());
            assertThat(platform.turnId()).isEqualTo(TURN);
            assertThat(platform.leaseEpoch()).isEqualTo(1L);
        });
    }

    @Test
    void childContextIsBuiltFromScratch() {
        ScriptedModel parent = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_parent_probe", PROBE, Map.of("text", "parent"))
                        .toolCall("call_spawn", PlatformToolNames.SPAWN_SUBAGENT, spawn("researcher", "probe it")))
                .respond(r -> r.text("done"))
                .build();
        ScriptedModel child = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_child_probe", PROBE, Map.of("text", "child")))
                .respond(r -> r.text("child done"))
                .build();
        Probe probe = new Probe();
        EngineHarness harness = harness(parent, child, probe, 8, subagent("researcher", Duration.ofSeconds(30)));
        CustomToolDeclaration weather = new CustomToolDeclaration(
                "get_weather", "Weather of a city.", RecordingTool.TEXT_SCHEMA, SideEffect.NONE);
        TurnContext ctx = harness.claim(
                MAIN_THREAD, TURN, 1, PARENT_ALLOWS, Map.of("researcher", CHILD_ALLOWS), null, List.of(weather));

        harness.run(ctx, EngineHarness.userText("secret parent message"));

        String childThread = harness.threads.threads().getFirst().threadId();
        RuntimeContext parentCtx = probe.contextOf(MAIN_THREAD);
        RuntimeContext childCtx = probe.contextOf(childThread);
        assertThat(childCtx.getSessionId()).isEqualTo(childThread);
        assertThat(childCtx.getUserId()).isEqualTo(parentCtx.getUserId());
        // 父线程的自定义工具（ToolRequestConfig）、AgentState 与文件系统对象都没有带到子线程
        assertThat(parentCtx.getToolRequestConfig()).isNotNull();
        assertThat(childCtx.getToolRequestConfig()).isNull();
        assertThat(parentCtx.getAgentState()).isNotNull();
        assertThat(childCtx.getAgentState()).isNotSameAs(parentCtx.getAgentState());
        assertThat(childCtx.getAgentState().getContext().toString()).doesNotContain("secret parent message");
        assertThat(parentCtx.get(AbstractFilesystem.class)).isNotNull();
        assertThat(childCtx.get(AbstractFilesystem.class)).isNotSameAs(parentCtx.get(AbstractFilesystem.class));
        // 平台每轮的标记只属于父线程
        assertThat(parentCtx.get(SubagentSlot.class)).isNotNull();
        assertThat(childCtx.get(SubagentSlot.class)).isNull();
        assertThat(childCtx.get(SubagentQuota.class)).isNull();
        assertThat(childCtx.get(BatchCheckpoint.class)).isNull();
        assertThat(childCtx.get(PlatformContext.class).threadId()).isEqualTo(childThread);
        assertThat(child.request(0).messages().toString()).doesNotContain("secret parent message");
    }

    @Test
    void permissionAskingSubagentIsTurnedIntoRejectionForParent() {
        ScriptedModel parent = ScriptedModel.builder()
                .respond(r ->
                        r.toolCall("call_spawn", PlatformToolNames.SPAWN_SUBAGENT, spawn("researcher", "probe it")))
                .respondTo((request, r) -> r.text("handled: " + lastToolResult(request)))
                .build();
        ScriptedModel child = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_probe", PROBE, Map.of("text", "x")))
                .build();
        Probe probe = new Probe();
        EngineHarness harness = harness(parent, child, probe, 8, subagent("researcher", Duration.ofSeconds(30)));
        // 子线程停在确认上：生产中只有工具自检的安全类 ask 会这样（显式 ask 规则已被 runtime 改写为 deny）
        PermissionPolicy asking = new PermissionPolicy(
                PermissionMode.DONT_ASK,
                List.of(new PermissionRule(PROBE, null, PermissionBehavior.ASK, PolicyLayer.AGENT_VERSION)));
        TurnContext ctx =
                harness.claim(MAIN_THREAD, TURN, 1, PARENT_ALLOWS, Map.of("researcher", asking), null, List.of());

        List<EngineEvent> events = harness.run(ctx, EngineHarness.userText("go"));

        assertThat(events.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
        AgentOutputEvent.ToolResult result = toolResult(events, "call_spawn");
        assertThat(result.state()).isEqualTo(AgentOutputEvent.ToolResultState.ERROR);
        assertThat(text(result)).contains("needed approval", PROBE, "were rejected");
        assertThat(probe.contexts).as("等待确认的调用没有执行").isEmpty();
        String childThread = harness.threads.threads().getFirst().threadId();
        assertThat(harness.events.eventsOf(childThread).getLast())
                .isEqualTo(new SessionEvent.ThreadStatusIdle(childThread, StopReason.TOOLS_DENIED));
        assertThat(harness.events.eventsOf(childThread))
                .as("子线程不产生待处理动作")
                .noneMatch(e -> e instanceof SessionEvent.StatusRequiresAction);
    }

    @Test
    void subagentWithoutAllowRuleGetsDeniedAndParentReceivesItsAnswer() {
        ScriptedModel parent = ScriptedModel.builder()
                .respond(r ->
                        r.toolCall("call_spawn", PlatformToolNames.SPAWN_SUBAGENT, spawn("researcher", "probe it")))
                .respond(r -> r.text("ok"))
                .build();
        ScriptedModel child = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_probe", PROBE, Map.of("text", "x")))
                .respondTo((request, r) -> r.text("could not probe: " + lastToolResult(request)))
                .build();
        Probe probe = new Probe();
        EngineHarness harness = harness(parent, child, probe, 8, subagent("researcher", Duration.ofSeconds(30)));
        PermissionPolicy noAllow = new PermissionPolicy(PermissionMode.DONT_ASK, List.of());
        TurnContext ctx =
                harness.claim(MAIN_THREAD, TURN, 1, PARENT_ALLOWS, Map.of("researcher", noAllow), null, List.of());

        List<EngineEvent> events = harness.run(ctx, EngineHarness.userText("go"));

        String childThread = harness.threads.threads().getFirst().threadId();
        assertThat(harness.events.eventsOf(childThread))
                .anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(AgentOutputEvent.ToolResult.class, r -> {
                    assertThat(r.state()).isEqualTo(AgentOutputEvent.ToolResultState.DENIED);
                    assertThat(r.permission()).isEqualTo(AgentOutputEvent.PermissionOutcome.DENIED);
                }));
        assertThat(probe.contexts).isEmpty();
        assertThat(text(toolResult(events, "call_spawn"))).startsWith("could not probe:");
    }

    @Test
    void timedOutSubagentIsCancelledAndParentReceivesTimeoutResult() throws Exception {
        ScriptedModel parent = ScriptedModel.builder()
                .respond(r ->
                        r.toolCall("call_spawn", PlatformToolNames.SPAWN_SUBAGENT, spawn("researcher", "slow work")))
                .respondTo((request, r) -> r.text("parent: " + lastToolResult(request)))
                .build();
        ScriptedModel child = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_probe", PROBE, Map.of("text", "slow")))
                .build();
        Probe probe = new Probe().blocking(1);
        EngineHarness harness = harness(parent, child, probe, 8, subagent("researcher", Duration.ofSeconds(1)));
        try {
            List<EngineEvent> events = harness.run(claim(harness), EngineHarness.userText("go"));

            AgentOutputEvent.ToolResult result = toolResult(events, "call_spawn");
            assertThat(result.state()).isEqualTo(AgentOutputEvent.ToolResultState.ERROR);
            assertThat(text(result)).contains("did not finish within 1 seconds and was cancelled");
            assertThat(events.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
            String childThread = harness.threads.threads().getFirst().threadId();
            assertThat(harness.events.eventsOf(childThread).getLast())
                    .isEqualTo(new SessionEvent.ThreadStatusIdle(childThread, StopReason.INTERRUPTED));
            assertThat(harness.ledger.invocations())
                    .filteredOn(i -> i.start().toolName().equals(PROBE))
                    .singleElement()
                    .satisfies(i ->
                            assertThat(i.status()).as("取消订阅传到子线程的工具").isEqualTo(ToolLedgerPort.Status.INTERRUPTED));
            assertThat(harness.leases.find(childThread)).isEmpty();
        } finally {
            probe.release.countDown();
        }
    }

    @Test
    void spawnsBeyondPerTurnLimitAreRejected() {
        ScriptedModel parent = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_a", PlatformToolNames.SPAWN_SUBAGENT, spawn("worker", "a")))
                .respond(r -> r.toolCall("call_b", PlatformToolNames.SPAWN_SUBAGENT, spawn("worker", "b"))
                        .toolCall("call_c", PlatformToolNames.SPAWN_SUBAGENT, spawn("worker", "c")))
                .respond(r -> r.text("done"))
                .build();
        ScriptedModel child = ScriptedModel.builder()
                .respondTo((request, r) -> r.text("answer " + request.lastUserText()))
                .respondTo((request, r) -> r.text("answer " + request.lastUserText()))
                .build();
        EngineHarness harness = harness(parent, child, new Probe(), 2, subagent("worker", Duration.ofSeconds(30)));

        List<EngineEvent> events = harness.run(claim(harness, "worker"), EngineHarness.userText("go"));

        assertThat(text(toolResult(events, "call_a"))).isEqualTo("answer a");
        assertThat(text(toolResult(events, "call_b"))).isEqualTo("answer b");
        AgentOutputEvent.ToolResult rejected = toolResult(events, "call_c");
        assertThat(rejected.state()).isEqualTo(AgentOutputEvent.ToolResultState.ERROR);
        assertThat(text(rejected)).contains("limit of subagents per turn has been reached");
        assertThat(harness.threads.threads()).as("被拒绝的调用不创建子线程").hasSize(2);
        assertThat(child.requests()).hasSize(2);
        assertThat(events.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
    }

    @Test
    void subagentThatCannotBeAssembledEndsItsThreadWithAnErrorAndTheParentContinues() {
        ScriptedModel parent = ScriptedModel.builder()
                .respond(r ->
                        r.toolCall("call_spawn", PlatformToolNames.SPAWN_SUBAGENT, spawn("researcher", "probe it")))
                .respondTo((request, r) -> r.text("parent: " + lastToolResult(request)))
                .build();
        ScriptedModel child = ScriptedModel.builder().build();
        // 子智能体引用了配置中不存在的技能，实例装配失败：子线程已经创建，必须以错误结束而不是停在 thread_created
        SubagentSpec broken = new SubagentSpec(
                "researcher",
                "Looks things up.",
                ModelSpec.of(CHILD_MODEL),
                "You are the researcher subagent.",
                Set.of(PROBE),
                List.of(new SkillRef("skl_missing", 1, "missing")),
                PermissionPolicy.empty(),
                10,
                Duration.ofSeconds(30));
        EngineHarness harness = harness(parent, child, new Probe(), 8, broken);

        List<EngineEvent> events = harness.run(claim(harness), EngineHarness.userText("go"));

        assertThat(events.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
        AgentOutputEvent.ToolResult result = toolResult(events, "call_spawn");
        assertThat(result.state()).isEqualTo(AgentOutputEvent.ToolResultState.ERROR);
        assertThat(text(result)).startsWith("Error: Subagent 'researcher' failed");
        String childThread = harness.threads.threads().getFirst().threadId();
        List<EngineEvent> childEvents = harness.events.eventsOf(childThread);
        assertThat(childEvents.getFirst()).isInstanceOf(SessionEvent.ThreadCreated.class);
        assertThat(childEvents)
                .anySatisfy(e -> assertThat(e)
                        .isInstanceOfSatisfying(
                                SessionEvent.Error.class,
                                error -> assertThat(error.retryStatus()).isEqualTo(SessionEvent.RetryStatus.TERMINAL)));
        assertThat(childEvents.getLast()).isEqualTo(new SessionEvent.ThreadStatusIdle(childThread, StopReason.ERROR));
        assertThat(child.requests()).isEmpty();
        assertThat(harness.leases.find(childThread)).isEmpty();
    }

    /** P12：同一批中并行拉起两个同名子智能体，两条子线程的事件互不混淆；中断父线程时两条子线程都停止。 */
    @Test
    void parallelSameNameSubagentsKeepEventsApartAndStopOnInterrupt() throws Exception {
        ScriptedModel parent = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_alpha", PlatformToolNames.SPAWN_SUBAGENT, spawn("worker", "alpha"))
                        .toolCall("call_beta", PlatformToolNames.SPAWN_SUBAGENT, spawn("worker", "beta")))
                .build();
        ScriptedModel child = ScriptedModel.builder()
                .respondTo(SubagentScenarioTest::probeThenAnswer)
                .respondTo(SubagentScenarioTest::probeThenAnswer)
                .respondTo(SubagentScenarioTest::probeThenAnswer)
                .respondTo(SubagentScenarioTest::probeThenAnswer)
                .build();
        Probe probe = new Probe().blocking(2);
        EngineHarness harness = harness(parent, child, probe, 8, subagent("worker", Duration.ofSeconds(60)));
        TurnContext ctx = claim(harness, "worker");
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        Flux<EngineEvent> turn = harness.stream(ctx, EngineHarness.userText("go"));
        CountDownLatch done = new CountDownLatch(1);
        Disposable subscription =
                turn.doOnNext(events::add).doFinally(signal -> done.countDown()).subscribe();
        try {
            assertThat(probe.started.await(20, TimeUnit.SECONDS))
                    .as("两个子智能体都在执行工具")
                    .isTrue();
            assertThat(harness.threads.threads()).hasSize(2);

            assertThat(harness.engine.interrupt(new ThreadRef(EngineHarness.SESSION, MAIN_THREAD)))
                    .isTrue();
            probe.release.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            probe.release.countDown();
            subscription.dispose();
        }

        EngineHarness.assertExactlyOneTerminalAtTheEnd(events);
        assertThat(events.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.INTERRUPTED));
        assertThat(parent.requests()).as("父线程被中断后没有再推理").hasSize(1);
        assertThat(child.requests()).as("子线程被中断后没有再推理").hasSize(2);

        Map<String, String> threadByTask = harness.threads.threads().stream()
                .collect(Collectors.toMap(
                        t -> harness.events.eventsOf(t.threadId()).stream()
                                .filter(AgentOutputEvent.ToolUse.class::isInstance)
                                .map(e -> (String)
                                        ((AgentOutputEvent.ToolUse) e).input().get("text"))
                                .findFirst()
                                .orElseThrow(),
                        InMemoryThreadRegistry.ChildThread::threadId));
        assertThat(threadByTask).containsOnlyKeys("alpha", "beta");
        assertThat(threadByTask.get("alpha")).isNotEqualTo(threadByTask.get("beta"));
        for (Map.Entry<String, String> entry : threadByTask.entrySet()) {
            String own = entry.getKey();
            String other = own.equals("alpha") ? "beta" : "alpha";
            List<EngineEvent> childEvents = harness.events.eventsOf(entry.getValue());
            assertThat(childEvents.toString()).contains("probe:" + own).doesNotContain(other);
            assertThat(childEvents.getLast())
                    .isEqualTo(new SessionEvent.ThreadStatusIdle(entry.getValue(), StopReason.INTERRUPTED));
            assertThat(harness.threads.threads())
                    .filteredOn(t -> t.threadId().equals(entry.getValue()))
                    .singleElement()
                    .satisfies(t -> assertThat(t.parentToolUseId()).isEqualTo("call_" + own));
        }
        assertThat(text(toolResult(events, "call_alpha"))).contains("was interrupted before it finished");
        assertThat(text(toolResult(events, "call_beta"))).contains("was interrupted before it finished");
    }

    @Test
    void subagentSharesSessionSandbox() {
        ScriptedModel parent = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_spawn", PlatformToolNames.SPAWN_SUBAGENT, spawn("builder", "build it")))
                .respond(r -> r.text("done"))
                .build();
        ScriptedModel child = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_bash", PlatformToolNames.BASH, Map.of("command", "make build")))
                .respond(r -> r.text("built"))
                .build();
        FakeSandbox sandbox = new FakeSandbox().respond(command -> new ExecResult(0, "ok", "", false));
        EngineHarness harness = EngineHarness.withSubagents(
                parent,
                spec -> spec.tools(PlatformToolNames.SPAWN_SUBAGENT, PlatformToolNames.BASH)
                        .subagent(new SubagentSpec(
                                "builder",
                                "Runs build commands.",
                                ModelSpec.of(CHILD_MODEL),
                                "You are the builder subagent.",
                                Set.of(PlatformToolNames.BASH),
                                List.of(),
                                PermissionPolicy.empty(),
                                10,
                                Duration.ofSeconds(30))),
                Map.of(CHILD_MODEL, child),
                8,
                Map.of(),
                sandbox);
        TurnContext ctx =
                harness.claim(MAIN_THREAD, TURN, 1, PARENT_ALLOWS, Map.of("builder", CHILD_ALLOWS), null, List.of());

        List<EngineEvent> events = harness.run(ctx, EngineHarness.userText("go"));

        assertThat(text(toolResult(events, "call_spawn"))).isEqualTo("built");
        assertThat(sandbox.commands()).anySatisfy(command -> assertThat(command).contains("make build"));
        assertThat(harness.ledger.invocations())
                .filteredOn(i -> i.start().toolName().equals(PlatformToolNames.SPAWN_SUBAGENT))
                .singleElement()
                .satisfies(i -> assertThat(i.start().sideEffect()).isEqualTo(SideEffect.NON_IDEMPOTENT));
    }

    private static void probeThenAnswer(RecordedRequest request, ScriptedResponse response) {
        if (request.toolResults().isEmpty()) {
            String task = request.lastUserText();
            response.toolCall("call_probe_" + task, PROBE, Map.of("text", task));
        } else {
            response.text("answer: " + lastToolResult(request));
        }
    }

    private static EngineHarness harness(
            ScriptedModel parent, ScriptedModel child, Probe probe, int maxSubagents, SubagentSpec subagent) {
        return EngineHarness.withSubagents(
                parent,
                spec -> spec.tools(PlatformToolNames.SPAWN_SUBAGENT, PROBE).subagent(subagent),
                Map.of(CHILD_MODEL, child),
                maxSubagents,
                Map.of(PROBE, probe::create),
                null);
    }

    private static SubagentSpec subagent(String name, Duration timeout) {
        return new SubagentSpec(
                name,
                "Looks things up.",
                ModelSpec.of(CHILD_MODEL),
                "You are the " + name + " subagent.",
                Set.of(PROBE),
                List.of(),
                PermissionPolicy.empty(),
                10,
                timeout);
    }

    private static TurnContext claim(EngineHarness harness) {
        return claim(harness, "researcher");
    }

    private static TurnContext claim(EngineHarness harness, String subagent) {
        return harness.claim(MAIN_THREAD, TURN, 1, PARENT_ALLOWS, Map.of(subagent, CHILD_ALLOWS), null, List.of());
    }

    private static Map<String, Object> spawn(String subagent, String task) {
        return Map.of("subagent", subagent, "task", task);
    }

    private static PermissionRule allow(String tool) {
        return new PermissionRule(tool, null, PermissionBehavior.ALLOW, PolicyLayer.AGENT_VERSION);
    }

    private static String userId() {
        return AgentScopeIdentity.userId(EngineHarness.PROJECT, null);
    }

    private static AgentOutputEvent.ToolResult toolResult(List<EngineEvent> events, String toolUseId) {
        return EngineHarness.of(events, AgentOutputEvent.ToolResult.class).stream()
                .filter(r -> r.toolUseId().equals(toolUseId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有工具结果 " + toolUseId + "：" + events));
    }

    private static String text(AgentOutputEvent.ToolResult result) {
        return result.content().stream()
                .map(c -> ((MessageContent.Text) c).text())
                .collect(Collectors.joining());
    }

    private static String lastToolResult(RecordedRequest request) {
        List<ToolResultBlock> results = request.toolResults();
        if (results.isEmpty()) {
            return "";
        }
        return results.getLast().getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    /**
     * 以平台工具名注册的探针：记录每次调用的 {@code RuntimeContext}，回显 {@code probe:<text>}；{@link #blocking} 后执行到
     * {@link #release} 放行。每个智能体实例一个工具对象，记录共享。
     */
    static final class Probe {

        final List<RuntimeContext> contexts = new CopyOnWriteArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(0);
        private boolean blocking;

        Probe blocking(int callers) {
            this.blocking = true;
            this.started = new CountDownLatch(callers);
            return this;
        }

        PlatformTool create(PlatformTool.ToolServices services) {
            return new PlatformTool(
                    new PlatformTool.Spec(
                            PROBE,
                            "Probe tool for tests.",
                            RecordingTool.TEXT_SCHEMA,
                            SideEffect.IDEMPOTENT,
                            RuleSyntax.none(),
                            true),
                    services) {
                @Override
                protected ToolOutput execute(ToolCall call) throws Exception {
                    contexts.add(call.runtimeContext());
                    if (blocking) {
                        started.countDown();
                        release.await(30, TimeUnit.SECONDS);
                    }
                    return ToolOutput.ok("probe:" + call.string("text"));
                }
            };
        }

        RuntimeContext contextOf(String threadId) {
            return contexts.stream()
                    .filter(ctx -> threadId.equals(ctx.getSessionId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("线程 " + threadId + " 没有调用探针"));
        }
    }
}
