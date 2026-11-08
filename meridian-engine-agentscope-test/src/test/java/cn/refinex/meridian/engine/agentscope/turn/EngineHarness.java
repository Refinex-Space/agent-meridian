package cn.refinex.meridian.engine.agentscope.turn;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.engine.agentscope.assembly.AgentInstanceCache;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformMiddlewareProvider;
import cn.refinex.meridian.engine.agentscope.state.FencedAgentStateStore;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.RecordingMiddlewareProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticToolProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestAssembly;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryAgentConfig;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryAgentStateRepository;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryBlobStore;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryInbox;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryLeaseRegistry;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.AgentTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Flux;

/**
 * 引擎场景测试的组装：真实装配的 {@code HarnessAgent}（{@code ScriptedModel}、平台中间件）、
 * {@link FencedAgentStateStore} 加内存端口、{@link AgentScopeEngine}。每个用例用自己的线程 ID。
 */
final class EngineHarness {

    static final String TENANT = "ten_test";
    static final String PROJECT = "prj_test";
    static final String SESSION = "ses_test";
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    final ScriptedModel model;
    final InMemoryLeaseRegistry leases = new InMemoryLeaseRegistry();
    final InMemoryInbox inbox = new InMemoryInbox();
    final InMemoryAgentStateRepository states = new InMemoryAgentStateRepository();
    final InMemoryBlobStore blobs = new InMemoryBlobStore();
    final InMemoryAgentConfig configs = new InMemoryAgentConfig();
    final ResolvedAgentSpec spec;
    final AgentInstanceCache instances;
    final AgentScopeEngine engine;

    private EngineHarness(
            ScriptedModel model, ResolvedAgentSpec spec, List<AgentTool> tools, List<MiddlewareBase> extraMiddlewares) {
        this.model = model;
        this.spec = spec;
        List<MiddlewareBase> middlewares =
                new ArrayList<>(new PlatformMiddlewareProvider(leases, inbox).middlewares(null));
        middlewares.addAll(extraMiddlewares);
        TestAssembly assembly = TestAssembly.builder(model)
                .stateStore(new FencedAgentStateStore(states, leases))
                .middlewares(new RecordingMiddlewareProvider(middlewares, middlewares))
                .tools(new StaticToolProvider(List.of(), tools))
                .build();
        configs.register(spec).register(TestSpecs.noneEnvironment());
        this.instances = new AgentInstanceCache(assembly.assembler());
        this.engine = new AgentScopeEngine(instances, configs, leases, blobs);
    }

    static EngineHarness create(ScriptedModel model, AgentTool... tools) {
        return create(model, UnaryOperator.identity(), List.of(), tools);
    }

    static EngineHarness create(
            ScriptedModel model,
            UnaryOperator<TestSpecs.AgentSpecBuilder> spec,
            List<MiddlewareBase> extraMiddlewares,
            AgentTool... tools) {
        return new EngineHarness(model, spec.apply(TestSpecs.agent()).build(), List.of(tools), extraMiddlewares);
    }

    /**
     * 模拟 Worker 认领：在 {@code LeaseRegistry} 与"数据库"中登记线程的租约，返回本轮的上下文。
     */
    TurnContext claim(String threadId, String turnId, long epoch, PermissionPolicy permissions) {
        return claim(threadId, turnId, epoch, permissions, null, List.of());
    }

    TurnContext claim(
            String threadId,
            String turnId,
            long epoch,
            PermissionPolicy permissions,
            String endUserId,
            List<CustomToolDeclaration> customTools) {
        leases.grant(threadId, turnId, epoch);
        states.activate(threadId, epoch);
        PlatformContext platform = PlatformContext.builder()
                .tenantId(TENANT)
                .projectId(PROJECT)
                .sessionId(SESSION)
                .threadId(threadId)
                .turnId(turnId)
                .leaseEpoch(epoch)
                .interaction(Interaction.ATTENDED)
                .build();
        EnvironmentSpec environment = TestSpecs.noneEnvironment();
        return new TurnContext(
                platform,
                endUserId,
                spec.agentVersionId(),
                environment.environmentId(),
                environment.revision(),
                permissions,
                null,
                customTools,
                null);
    }

    /** 模拟 Worker 在执行结束后移除租约条目。 */
    void release(TurnContext ctx) {
        leases.revoke(ctx.platform().threadId());
    }

    /** 执行一轮并收集全部事件，断言恰好以一个终态结束。 */
    List<EngineEvent> run(TurnContext ctx, TurnInput input) {
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        engine.runTurn(ctx, input).doOnNext(events::add).blockLast(TIMEOUT);
        assertExactlyOneTerminalAtTheEnd(events);
        return List.copyOf(events);
    }

    Flux<EngineEvent> stream(TurnContext ctx, TurnInput input) {
        return engine.runTurn(ctx, input);
    }

    static TurnInput.NewMessages userText(String text) {
        return new TurnInput.NewMessages(
                List.of(new InboundMessage("evt_" + Math.abs(text.hashCode()), List.of(new MessageContent.Text(text)))),
                null);
    }

    static boolean isTerminal(EngineEvent event) {
        return event instanceof SessionEvent.StatusIdle
                || event instanceof SessionEvent.StatusRequiresAction
                || event instanceof SessionEvent.StatusRescheduled;
    }

    static void assertExactlyOneTerminalAtTheEnd(List<EngineEvent> events) {
        assertThat(events).as("事件流非空").isNotEmpty();
        assertThat(events.stream().filter(EngineHarness::isTerminal).count())
                .as("恰好一个终态")
                .isEqualTo(1);
        assertThat(isTerminal(events.getLast())).as("终态是最后一个事件").isTrue();
    }

    @SuppressWarnings("unchecked")
    static <T extends EngineEvent> List<T> of(List<EngineEvent> events, Class<T> type) {
        return (List<T>) events.stream().filter(type::isInstance).toList();
    }
}
