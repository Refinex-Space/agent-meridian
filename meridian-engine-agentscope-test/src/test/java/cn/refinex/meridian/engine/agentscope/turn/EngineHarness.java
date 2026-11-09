package cn.refinex.meridian.engine.agentscope.turn;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.assembly.AgentAssembler;
import cn.refinex.meridian.engine.agentscope.assembly.AgentHandle;
import cn.refinex.meridian.engine.agentscope.assembly.AgentInstanceCache;
import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.assembly.TenantPromptProvider;
import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import cn.refinex.meridian.engine.agentscope.fs.PlatformWorkspace;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformMiddlewareProvider;
import cn.refinex.meridian.engine.agentscope.middleware.PolicyMiddleware;
import cn.refinex.meridian.engine.agentscope.sandbox.SandboxDriverSettings;
import cn.refinex.meridian.engine.agentscope.sandbox.TurnSandboxes;
import cn.refinex.meridian.engine.agentscope.state.FencedAgentStateStore;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.InMemoryWorkspaceFilesystems;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.RecordingMiddlewareProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.RecordingModelFactory;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticToolProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestAssembly;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryAgentConfig;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryAgentStateRepository;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryBlobStore;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryEventSink;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryInbox;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryLeaseRegistry;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemorySandboxStates;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryThreadRegistry;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryToolLedger;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryWorkspaceStore;
import cn.refinex.meridian.engine.agentscope.testkit.sandbox.FakeSandbox;
import cn.refinex.meridian.engine.agentscope.tools.BashTool;
import cn.refinex.meridian.engine.agentscope.tools.PlatformTool;
import cn.refinex.meridian.engine.agentscope.tools.PlatformToolProvider;
import cn.refinex.meridian.engine.agentscope.tools.mcp.McpConnector;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.AgentTool;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
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

    /** 平台上下文消息中的当前时间，固定以便断言。 */
    static final Instant NOW = Instant.parse("2026-11-08T08:30:00Z");

    final Model model;
    final InMemoryLeaseRegistry leases = new InMemoryLeaseRegistry();
    final InMemoryInbox inbox = new InMemoryInbox();
    final InMemoryAgentStateRepository states = new InMemoryAgentStateRepository();
    final InMemoryBlobStore blobs = new InMemoryBlobStore();
    final InMemoryAgentConfig configs = new InMemoryAgentConfig();
    final InMemoryToolLedger ledger = new InMemoryToolLedger();
    final InMemoryWorkspaceStore workspace = new InMemoryWorkspaceStore();
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final InMemorySandboxStates sandboxStates = new InMemorySandboxStates();
    /** 子线程的事件（含 {@code session.thread_*}），经 {@code EventSinkPort} 与内存的 {@code ThreadRegistryPort} 写入。 */
    final InMemoryEventSink events = new InMemoryEventSink();

    final InMemoryThreadRegistry threads = new InMemoryThreadRegistry(events);
    final ResolvedAgentSpec spec;
    final EnvironmentSpec environment;
    final AgentInstanceCache instances;
    final AgentScopeEngine engine;

    private EngineHarness(
            Model model,
            ResolvedAgentSpec spec,
            List<AgentTool> tools,
            List<MiddlewareBase> extraMiddlewares,
            Predicate<MiddlewareBase> keep) {
        this(
                model,
                spec,
                tools,
                extraMiddlewares,
                keep,
                false,
                List.of(),
                Map.of(),
                null,
                null,
                Map.of(),
                PolicyMiddleware.DEFAULT_MAX_SUBAGENTS_PER_TURN);
    }

    private EngineHarness(
            Model model,
            ResolvedAgentSpec spec,
            List<AgentTool> tools,
            List<MiddlewareBase> extraMiddlewares,
            Predicate<MiddlewareBase> keep,
            boolean platform,
            List<SkillDefinition> skills,
            Map<String, Function<PlatformTool.ToolServices, PlatformTool>> extraPlatformTools,
            McpConnector mcp,
            FakeSandbox sandbox,
            Map<String, Model> modelsById,
            int maxSubagentsPerTurn) {
        this.model = model;
        this.spec = spec;
        this.environment = sandbox == null ? TestSpecs.noneEnvironment() : TestSpecs.sandboxEnvironment();
        PlatformMiddlewareProvider provider = new PlatformMiddlewareProvider(leases, inbox, true, maxSubagentsPerTurn);
        List<MiddlewareBase> middlewares =
                new ArrayList<>(provider.middlewares(AgentProfile.main(spec, TestSpecs.noneEnvironment())));
        middlewares.removeIf(keep.negate());
        middlewares.addAll(extraMiddlewares);
        // 子智能体挂载生产提供者给出的子智能体中间件集合（不含 steer）
        List<MiddlewareBase> subagentMiddlewares = spec.subagents().isEmpty()
                ? middlewares
                : new ArrayList<>(provider.middlewares(
                        AgentProfile.subagent(spec, spec.subagents().getFirst().name(), TestSpecs.noneEnvironment())));
        subagentMiddlewares.removeIf(keep.negate());
        configs.register(spec).register(environment);
        skills.forEach(configs::register);
        AgentAssembler assembler;
        if (platform) {
            // 生产的工具、工作区与技能提供者，端口用内存实现
            PlatformWorkspace platformWorkspace = new PlatformWorkspace(
                    workspace,
                    configs,
                    InMemoryWorkspaceFilesystems.LOCAL_WORKSPACE_ROOT,
                    () -> TurnSandboxes.filesystemSpec(
                            SandboxDriverSettings.defaults(new SandboxDriverSettings.PortForward())));
            PlatformToolProvider toolProvider = PlatformToolProvider.withFileTools(
                            new PlatformTool.ToolServices(ledger, blobs))
                    .with(PlatformToolNames.BASH, BashTool.factory(BashTool.Settings.DEFAULTS));
            for (Map.Entry<String, Function<PlatformTool.ToolServices, PlatformTool>> extra :
                    extraPlatformTools.entrySet()) {
                toolProvider = toolProvider.with(extra.getKey(), extra.getValue());
            }
            if (mcp != null) {
                toolProvider = toolProvider.withMcp(mcp);
            }
            ToolProvider platformTools = toolProvider;
            ToolProvider combined = profile -> {
                ToolProvider.ToolBundle bundle = platformTools.tools(profile);
                List<AgentTool> all = new ArrayList<>(bundle.tools());
                all.addAll(tools);
                return new ToolProvider.ToolBundle(all, bundle.resources());
            };
            RecordingModelFactory models = new RecordingModelFactory(model);
            modelsById.forEach(models::register);
            assembler = new AgentAssembler(
                    models,
                    new FencedAgentStateStore(states, leases),
                    new RecordingMiddlewareProvider(middlewares, subagentMiddlewares),
                    combined,
                    platformWorkspace,
                    platformWorkspace.skills(),
                    TenantPromptProvider.fromPort(configs));
        } else {
            assembler = TestAssembly.builder(model)
                    .stateStore(new FencedAgentStateStore(states, leases))
                    .middlewares(new RecordingMiddlewareProvider(middlewares, middlewares))
                    .tools(new StaticToolProvider(List.of(), tools))
                    .tenantPrompts(TenantPromptProvider.fromPort(configs))
                    .build()
                    .assembler();
        }
        this.instances = new AgentInstanceCache(assembler);
        TurnSandboxes sandboxes = null;
        if (sandbox != null) {
            sandboxStates
                    .save(SESSION, new SandboxHandle("sbx-fake", "10.0.0.7", "fake-state"))
                    .block();
            sandboxes = new TurnSandboxes(sandboxStates, state -> sandbox);
        }
        this.engine = new AgentScopeEngine(
                instances,
                configs,
                leases,
                blobs,
                clock,
                sandboxes,
                new SubagentRunner(instances, leases, threads, events, blobs, clock));
    }

    static EngineHarness create(ScriptedModel model, AgentTool... tools) {
        return create(model, UnaryOperator.identity(), List.of(), tools);
    }

    static EngineHarness create(
            Model model,
            UnaryOperator<TestSpecs.AgentSpecBuilder> spec,
            List<MiddlewareBase> extraMiddlewares,
            AgentTool... tools) {
        return new EngineHarness(
                model, spec.apply(TestSpecs.agent()).build(), List.of(tools), extraMiddlewares, middleware -> true);
    }

    /**
     * 用生产的平台工具（{@code PlatformToolProvider} 的文件工具）、工作区（{@code PlatformWorkspace}，内存的
     * {@code WorkspaceStorePort}）与技能仓库装配；账本、对象存储、配置都是内存端口。
     *
     * @param model 模型
     * @param spec 智能体配置
     * @param skills 技能内容，登记到配置端口
     * @param extraTools 额外注册的测试工具
     * @return 组装
     */
    static EngineHarness withPlatform(
            Model model,
            UnaryOperator<TestSpecs.AgentSpecBuilder> spec,
            List<SkillDefinition> skills,
            AgentTool... extraTools) {
        return withPlatform(model, spec, skills, Map.of(), extraTools);
    }

    /**
     * 同 {@link #withPlatform(Model, UnaryOperator, List, AgentTool...)}，另经 {@code PlatformToolProvider.with} 登记平台工具
     * （新增平台工具的真实路径），工具用组装中的内存账本与对象存储。
     *
     * @param model 模型
     * @param spec 智能体配置
     * @param skills 技能内容
     * @param platformTools 额外的平台工具：工具名 → 工厂
     * @param extraTools 额外注册的测试工具
     * @return 组装
     */
    static EngineHarness withPlatform(
            Model model,
            UnaryOperator<TestSpecs.AgentSpecBuilder> spec,
            List<SkillDefinition> skills,
            Map<String, Function<PlatformTool.ToolServices, PlatformTool>> platformTools,
            AgentTool... extraTools) {
        return new EngineHarness(
                model,
                spec.apply(TestSpecs.agent()).build(),
                List.of(extraTools),
                List.of(),
                middleware -> true,
                true,
                skills,
                platformTools,
                null,
                null,
                Map.of(),
                PolicyMiddleware.DEFAULT_MAX_SUBAGENTS_PER_TURN);
    }

    /**
     * 同 {@link #withPlatform(Model, UnaryOperator, List, AgentTool...)}，另登记 MCP 连接器：智能体挂载的 MCP 服务在装配时连接、
     * 发现工具（T10b）。
     *
     * @param model 模型
     * @param spec 智能体配置
     * @param mcp MCP 连接器
     * @return 组装
     */
    static EngineHarness withMcp(Model model, UnaryOperator<TestSpecs.AgentSpecBuilder> spec, McpConnector mcp) {
        return new EngineHarness(
                model,
                spec.apply(TestSpecs.agent()).build(),
                List.of(),
                List.of(),
                middleware -> true,
                true,
                List.of(),
                Map.of(),
                mcp,
                null,
                Map.of(),
                PolicyMiddleware.DEFAULT_MAX_SUBAGENTS_PER_TURN);
    }

    /**
     * {@code sandbox} 档：生产的平台工具（含只在 sandbox 档注册的 {@code bash}）、工作区与技能仓库，会话沙箱是脚本化的
     * {@link FakeSandbox}，经内存的 {@code SandboxStatePort} 与 {@link TurnSandboxes} 每轮作为外部沙箱注入。
     *
     * @param model 模型
     * @param spec 智能体配置
     * @param sandbox 会话沙箱替身
     * @param extraTools 额外注册的测试工具
     * @return 组装
     */
    static EngineHarness withSandbox(
            Model model, UnaryOperator<TestSpecs.AgentSpecBuilder> spec, FakeSandbox sandbox, AgentTool... extraTools) {
        return new EngineHarness(
                model,
                spec.apply(TestSpecs.agent()).build(),
                List.of(extraTools),
                List.of(),
                middleware -> true,
                true,
                List.of(),
                Map.of(),
                null,
                sandbox,
                Map.of(),
                PolicyMiddleware.DEFAULT_MAX_SUBAGENTS_PER_TURN);
    }

    /**
     * 子智能体（T13）：生产的平台工具（{@code spawn_subagent} 由 {@code PlatformToolProvider} 加入）、主智能体与子智能体各自的
     * 中间件集合、内存的 {@code ThreadRegistryPort} 与 {@code EventSinkPort}（{@link #events}）。主智能体用 {@code model}，
     * {@code modelsById} 中登记的模型 ID 用对应的模型（例如子智能体的 {@code ScriptedModel}）。
     *
     * @param model 主智能体的模型（模型 ID 没有登记时的默认模型）
     * @param spec 智能体配置，声明子智能体
     * @param modelsById 模型 ID → 模型
     * @param maxSubagentsPerTurn 每轮拉起子智能体的上限
     * @param platformTools 额外的平台工具：工具名 → 工厂（子智能体的工具必须是平台工具名）
     * @param sandbox 会话沙箱替身；为 null 时是 {@code none} 档
     * @return 组装
     */
    static EngineHarness withSubagents(
            Model model,
            UnaryOperator<TestSpecs.AgentSpecBuilder> spec,
            Map<String, Model> modelsById,
            int maxSubagentsPerTurn,
            Map<String, Function<PlatformTool.ToolServices, PlatformTool>> platformTools,
            FakeSandbox sandbox) {
        return new EngineHarness(
                model,
                spec.apply(TestSpecs.agent()).build(),
                List.of(),
                List.of(),
                middleware -> true,
                true,
                List.of(),
                platformTools,
                null,
                sandbox,
                modelsById,
                maxSubagentsPerTurn);
    }

    /** 对照实验用：只保留 {@code keep} 为真的平台中间件。 */
    static EngineHarness createFiltered(
            Model model, UnaryOperator<TestSpecs.AgentSpecBuilder> spec, Predicate<MiddlewareBase> keep) {
        return new EngineHarness(model, spec.apply(TestSpecs.agent()).build(), List.of(), List.of(), keep);
    }

    /**
     * 模拟 Worker 认领：在 {@code LeaseRegistry} 与"数据库"中登记线程的租约，返回本轮的上下文。
     */
    /**
     * 在本租户的 {@code PlatformContext} 下租用主智能体实例（装配读取租户提示词时需要它），用于直接检查装配结果的用例。
     *
     * @return 实例凭据，用完 {@code instances.release}
     */
    AgentHandle acquire() {
        PlatformContext platform =
                PlatformContext.builder().tenantId(TENANT).projectId(PROJECT).build();
        return PlatformContexts.call(platform, () -> instances.acquire(spec, environment));
    }

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
        return claim(threadId, turnId, epoch, permissions, Map.of(), endUserId, customTools);
    }

    /** 同上，另给出每个子智能体预先合成的权限策略（runtime 由 {@code PermissionSynthesizer.subagents} 合成）。 */
    TurnContext claim(
            String threadId,
            String turnId,
            long epoch,
            PermissionPolicy permissions,
            Map<String, PermissionPolicy> subagentPolicies,
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
                .endUserId(endUserId)
                .build();
        return new TurnContext(
                platform,
                endUserId,
                spec.agentVersionId(),
                environment.environmentId(),
                environment.revision(),
                permissions,
                subagentPolicies,
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
