package cn.refinex.meridian.engine.agentscope.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.testkit.assembly.InMemoryWorkspaceFilesystems;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.RecordingMiddlewareProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticSkillRepository;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticToolProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestAssembly;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.tool.RecordingTool;
import cn.refinex.meridian.engine.spi.spec.CompactionSpec;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SubagentSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import io.agentscope.harness.agent.middleware.AtPathExpansionMiddleware;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.middleware.InboxMiddleware;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import io.agentscope.harness.agent.middleware.MemoryMaintenanceMiddleware;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import io.agentscope.harness.agent.middleware.TranscriptMiddleware;
import io.agentscope.harness.agent.middleware.WorkspaceContextMiddleware;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 装配表的逐行断言（T03-A03）：每个测试方法的名字以「引擎装配」表中的配置项开头，能从构建结果读出的直接断言，
 * 读不出的通过注入的替身或行为断言。测试名与装配表的对照写在 {@code docs/poc/P01-运行基线.md}。
 */
class AgentAssemblerTest {

    private static final String MAIN_THREAD = "thr_main";
    private static final String USER = "prj_test:_";

    private final ScriptedModel model =
            ScriptedModel.builder().respond(r -> r.text("你好", 1).usage(12, 2)).build();

    @Test
    void model_isTheRoutedModelFromTheFactoryWithoutModelRegistry() {
        ModelSpec modelSpec = new ModelSpec("mdl_primary", "mdl_backup", 512, 0.2);
        TestAssembly assembly = TestAssembly.builder(model).build();

        HarnessAgent agent =
                assemble(assembly, TestSpecs.agent().model(modelSpec).build());

        assertThat(agent.getModel()).isSameAs(model);
        assertThat(assembly.modelFactory().requested())
                .as("主模型与备用模型都交给工厂（RoutedModel 内部切换），只调用一次")
                .containsExactly(modelSpec);
        GenerateOptions options = agent.getDelegate().getGenerateOptions();
        assertThat(options.getMaxTokens()).isEqualTo(512);
        assertThat(options.getTemperature()).isEqualTo(0.2);
    }

    @Test
    void fallbackModel_isNotSetSoFirstSignalErrorsReachThePlatform() {
        ScriptedModel failing = ScriptedModel.builder()
                .failBeforeFirstChunk(new IllegalStateException("供应商不可用"))
                .build();
        TestAssembly assembly = TestAssembly.builder(failing).build();
        HarnessAgent agent = assemble(assembly, TestSpecs.agent().build());

        assertThat(agent.getDelegate().getModelConfig().fallbackModel()).isNull();
        List<Object> signals = run(agent, "你好");
        assertThat(signals.getLast())
                .as("首包前错误原样传出，没有被 AgentScope 切换到备用模型")
                .isInstanceOf(IllegalStateException.class)
                .hasFieldOrPropertyWithValue("message", "供应商不可用");
        assertThat(failing.requests()).hasSize(1);
    }

    @Test
    void maxRetries_isOneSoAgentScopeNeverRetriesAndModelsReceiveSingleAttempt() {
        TestAssembly assembly = TestAssembly.builder(model).build();
        HarnessAgent agent = assemble(assembly, TestSpecs.agent().build());

        assertThat(agent.getDelegate().getModelConfig().maxRetries()).isEqualTo(1);
        run(agent, "你好");
        assertThat(model.request(0).options().getExecutionConfig().getMaxAttempts())
                .as("模型收到的总尝试次数为 1，底层供应商模型类不会自行重试")
                .isEqualTo(1);
    }

    @Test
    void stateStore_isTheInjectedFencedStore() {
        TestAssembly assembly = TestAssembly.builder(model).build();

        HarnessAgent agent = assemble(assembly, TestSpecs.agent().build());

        assertThat(agent.getStateStore()).isSameAs(assembly.stateStore());
        assertThat(agent.getDelegate().getStateStore()).isSameAs(assembly.stateStore());
    }

    @Test
    void enablePendingToolRecovery_isTrue() {
        HarnessAgent agent =
                assemble(TestAssembly.builder(model).build(), TestSpecs.agent().build());

        assertThat(agent.getDelegate().isPendingToolRecoveryEnabled()).isTrue();
    }

    @Test
    void maxIters_followsTheAgentConfiguration() {
        TestAssembly assembly = TestAssembly.builder(model).build();

        assertThat(assemble(assembly, TestSpecs.agent().build()).getMaxIters())
                .isEqualTo(ResolvedAgentSpec.DEFAULT_MAX_ITERS);
        assertThat(assemble(assembly, TestSpecs.agent().maxIters(200).build()).getMaxIters())
                .isEqualTo(200);
    }

    @Test
    void filesystem_noneTierUsesCompositeOfPlatformStoreAndReadOnlyProtectedRoutes() {
        TestAssembly assembly = TestAssembly.builder(model).build();
        InMemoryWorkspaceFilesystems fs = assembly.filesystems();

        HarnessAgent agent = assemble(assembly, TestSpecs.agent().build());

        assertThat(agent.getWorkspaceManager().getFilesystem()).isInstanceOf(CompositeFilesystem.class);
        CompositeFilesystem composite =
                (CompositeFilesystem) agent.getWorkspaceManager().getFilesystem();
        assertThat(composite.filesystemFor("/notes/plan.md")).isSameAs(fs.platformStore());
        assertThat(composite.filesystemFor("/large_tool_results/call_1.txt")).isSameAs(fs.platformStore());
        for (String prefix : InMemoryWorkspaceFilesystems.PROTECTED_PREFIXES) {
            String path = prefix.endsWith("/") ? prefix + "x/SKILL.md" : prefix;
            assertThat(composite.filesystemFor(path)).as(path).isSameAs(fs.protectedRoute(prefix));
        }
        assertThat(fs.sandboxSpecs()).as("none 档不使用沙箱规范").isEmpty();
    }

    @Test
    void filesystem_sandboxTierUsesSandboxSpecWithRoutesAndNoProjection() {
        TestAssembly assembly = TestAssembly.builder(model).build();
        InMemoryWorkspaceFilesystems fs = assembly.filesystems();

        HarnessAgent agent = assemble(assembly, TestSpecs.agent().build(), TestSpecs.sandboxEnvironment());

        assertThat(agent.getWorkspaceManager().getFilesystem()).isInstanceOf(RoutedSandboxFilesystem.class);
        RoutedSandboxFilesystem routed =
                (RoutedSandboxFilesystem) agent.getWorkspaceManager().getFilesystem();
        assertThat(routed.backendFor("/large_tool_results/call_1.txt")).isSameAs(fs.largeToolResults());
        assertThat(routed.backendFor("/skills/x/SKILL.md")).isSameAs(fs.protectedRoute("/skills/"));
        assertThat(routed.backendFor("/AGENTS.md")).isSameAs(fs.protectedRoute("/AGENTS.md"));
        assertThat(routed.backendFor("/outputs/report.md")).as("其余路径在沙箱内").isSameAs(routed.primary());
        assertThat(fs.sandboxSpecs()).hasSize(1);
        assertThat(Reflection.field(fs.sandboxSpecs().getFirst(), "workspaceProjectionEnabled"))
                .as("默认的工作区投影关闭")
                .isEqualTo(false);
    }

    @Test
    void disableBuiltinTools_toolkitHoldsOnlyPlatformToolsAndHarnessReservedOnes() {
        List<AgentTool> platformTools = List.of(
                RecordingTool.echo("read_file"),
                RecordingTool.echo("write_file"),
                RecordingTool.echo("web_search"),
                RecordingTool.echo("web_fetch"),
                RecordingTool.echo("bash"));
        TestAssembly assembly = TestAssembly.builder(model)
                .tools(new StaticToolProvider(platformTools, List.of()))
                .build();
        ResolvedAgentSpec spec = TestSpecs.agent()
                .tools("read_file", "write_file", "web_search", "web_fetch", "bash")
                .build();

        for (EnvironmentSpec environment : List.of(TestSpecs.noneEnvironment(), TestSpecs.sandboxEnvironment())) {
            HarnessAgent agent = assemble(assembly, spec, environment);

            // disableWebTools、disableFilesystemTools、disableShellTool、disableSubagents、disableDynamicSubagents、
            // disableMemoryTools：内置的 web、文件、execute、agent_spawn/task、memory/session 工具都不存在
            assertThat(agent.getToolkit().getToolNames())
                    .as("%s 档的工具集", environment.tier().value())
                    .containsExactlyInAnyOrder(
                            "read_file",
                            "write_file",
                            "web_search",
                            "web_fetch",
                            "bash",
                            "wait_async_results",
                            "load_skill_through_path");
            for (AgentTool tool : platformTools) {
                assertThat(agent.getToolkit().getTool(tool.getName()))
                        .as("同名工具是平台工具，不是内置工具")
                        .isSameAs(tool);
            }
            assertThat(agent.getSubagentAgentManager()).isNull();
        }
    }

    @Test
    void disableToolsConfig_workspaceToolsJsonIsIgnored() {
        InMemoryWorkspaceFilesystems fs = new InMemoryWorkspaceFilesystems();
        fs.protectedRoute("/tools.json")
                .write(RuntimeContext.empty(), "/tools.json", "{\"deny\": [\"knowledge_search\"]}");
        AgentTool knowledgeSearch = RecordingTool.echo("knowledge_search");
        TestAssembly assembly = TestAssembly.builder(model)
                .filesystems(fs)
                .tools(StaticToolProvider.always(knowledgeSearch))
                .build();

        HarnessAgent agent = assemble(assembly, TestSpecs.agent().build());

        assertThat(agent.getToolkit().getToolNames()).contains("knowledge_search");
        // 对照：同样的工作区，不禁用 tools.json 时 knowledge_search 会被过滤掉，说明上面的断言不是空转
        HarnessAgent control = HarnessAgent.builder()
                .name("control")
                .model(model)
                .stateStore(assembly.stateStore())
                .abstractFilesystem(new CompositeFilesystem(fs.platformStore(), fs.protectedRoutes(null)))
                .toolkit(toolkitWith(knowledgeSearch))
                .disableWebTools()
                .disableMemoryHooks()
                // 对照组只改变被测的那一项；transcript 会写本机磁盘，同样关闭
                .disableTranscript()
                .build();
        assertThat(control.getToolkit().getToolNames()).doesNotContain("knowledge_search");
    }

    @Test
    void sysPrompt_layersPlatformBaseThenTenantThenAgent() {
        HarnessAgent agent = assemble(
                TestAssembly.builder(model)
                        .tenantPrompts(profile -> "按公司的报销制度回答。")
                        .build(),
                TestSpecs.agent().build());

        String prompt = agent.getDelegate().getSysPrompt();
        assertThat(prompt).startsWith(PlatformPrompt.BASE.strip());
        int tenant = prompt.indexOf(PlatformPrompt.TENANT_HEADING + "\n\n按公司的报销制度回答。");
        int agentLayer = prompt.indexOf(PlatformPrompt.AGENT_HEADING + "\n\n"
                + TestSpecs.agent().build().systemPrompt());
        assertThat(tenant)
                .as("租户层在平台基础提示词之后")
                .isGreaterThan(PlatformPrompt.BASE.strip().length() - 1);
        assertThat(agentLayer).as("智能体层在租户层之后").isGreaterThan(tenant);
        assertThat(prompt).as("声明来源标签内的内容是数据").contains("<external_content").contains("untrusted data");
    }

    @Test
    void disableWorkspaceContext_agentsMdAndDateAreNotInjectedIntoTheSystemPrompt() {
        HarnessAgent agent =
                assemble(TestAssembly.builder(model).build(), TestSpecs.agent().build());

        assertThat(agent.getDelegate().getMiddlewares())
                .as("工作区上下文中间件会重复注入 AGENTS.md 并带上日期（D29），装配时关闭")
                .noneMatch(m -> m instanceof WorkspaceContextMiddleware);
    }

    @Test
    void disableAtPathExpansion_userMessagesAreNotExpandedInEitherTier() {
        TestAssembly assembly = TestAssembly.builder(model).build();
        HarnessAgent none = assemble(assembly, TestSpecs.agent().build());
        HarnessAgent sandbox = assemble(assembly, TestSpecs.agent().build(), TestSpecs.sandboxEnvironment());

        // sandbox 档的文件系统不是 CompositeFilesystem，@路径 会被展开为文件内容，读取绕过权限、账本与审计（ADR-0041）
        assertThat(none.getDelegate().getMiddlewares()).noneMatch(AtPathExpansionMiddleware.class::isInstance);
        assertThat(sandbox.getDelegate().getMiddlewares()).noneMatch(AtPathExpansionMiddleware.class::isInstance);
    }

    @Test
    void toolExecutionConfig_timeoutCoversThePlatformToolLimitsWithoutRetry() {
        HarnessAgent agent =
                assemble(TestAssembly.builder(model).build(), TestSpecs.agent().build());

        ExecutionConfig config = agent.getDelegate().getToolExecutionConfig();
        // 2.0.4 的默认值 5 分钟会截断 bash、spawn_subagent 的 600 秒上限（ADR-0041）
        assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(660));
        assertThat(config.getTimeout()).isGreaterThan(ExecutionConfig.TOOL_DEFAULTS.getTimeout());
        assertThat(config.getMaxAttempts()).isEqualTo(1);
    }

    @Test
    void workspace_localRootIsTheProvidersPlatformControlledDirectory() {
        TestAssembly assembly = TestAssembly.builder(model).build();

        HarnessAgent agent = assemble(assembly, TestSpecs.agent().build());

        // 不设时 harness 用 ${user.dir}/.agentscope/workspace 并从中回退读取（D05）
        assertThat(agent.getWorkspaceManager().getWorkspace())
                .isEqualTo(assembly.filesystems().localWorkspaceRoot());
    }

    @Test
    void disableMemoryHooksAndTranscriptAndTracing_noSuchMiddlewareInstalled() {
        HarnessAgent agent =
                assemble(TestAssembly.builder(model).build(), TestSpecs.agent().build());

        assertThat(agent.getDelegate().getMiddlewares())
                .noneMatch(m -> m instanceof MemoryFlushMiddleware
                        || m instanceof MemoryMaintenanceMiddleware
                        || m instanceof TranscriptMiddleware
                        || m instanceof AgentTraceMiddleware);
    }

    @Test
    void disableDefaultWorkspaceSkillsAndDynamicSkills_onlyThePlatformRepositoryFrozenAtBuild() {
        StaticSkillRepository skills =
                new StaticSkillRepository(List.of(new AgentSkill("report-writer", "撰写报告", "按模板撰写报告。", Map.of())));
        TestAssembly assembly = TestAssembly.builder(model).skills(skills).build();
        ResolvedAgentSpec spec = TestSpecs.agent().build();

        HarnessAgent agent = assemble(assembly, spec);

        // skillRepository：平台技能仓库按装配对象解析
        assertThat(agent.getSkillRepositories()).containsExactly(skills);
        assertThat(agent.getSkillRepositories()).noneMatch(WorkspaceSkillRepository.class::isInstance);
        assertThat(skills.profiles()).extracting(AgentProfile::agent).containsExactly(spec);
        HarnessSkillMiddleware skillMiddleware = middleware(agent, HarnessSkillMiddleware.class);
        assertThat(skillMiddleware.isFrozen()).as("disableDynamicSkills 冻结技能清单").isTrue();
        assertThat(skills.queriedContexts()).as("冻结时用空的 RuntimeContext 查询一次").hasSize(1);
    }

    @Test
    void messageBus_isTheNoopBusAndAsyncToolRegistryIsNoopSoReasoningNeverReadsTheWorkspaceBus() {
        TestAssembly assembly = TestAssembly.builder(model).build();
        HarnessAgent agent = assemble(assembly, TestSpecs.agent().build());

        InboxMiddleware inbox = middleware(agent, InboxMiddleware.class);
        assertThat(Reflection.field(inbox, "messageBus")).isSameAs(NoopMessageBus.INSTANCE);
        assertThat(Reflection.field(inbox, "asyncToolRegistry")).isSameAs(NoopAsyncToolRegistry.INSTANCE);
        run(agent, "你好");
        assertThat(assembly.filesystems().platformBackend().accessedKeys())
                .as("推理前没有读取工作区中的消息总线与异步工具登记")
                .noneMatch(key -> key.contains(".agentscope/bus"));

        // 对照：不设置时 harness 的默认实现确实会在推理前读工作区
        InMemoryWorkspaceFilesystems fs = new InMemoryWorkspaceFilesystems();
        HarnessAgent control = HarnessAgent.builder()
                .name("control")
                .model(ScriptedModel.builder().respond(r -> r.text("你好")).build())
                .stateStore(assembly.stateStore())
                .abstractFilesystem(new CompositeFilesystem(fs.platformStore(), fs.protectedRoutes(null)))
                .disableWebTools()
                .disableMemoryHooks()
                // 对照组只改变被测的那一项；transcript 会写本机磁盘，同样关闭
                .disableTranscript()
                .build();
        run(control, "你好");
        assertThat(fs.platformBackend().accessedKeys()).anyMatch(key -> key.contains(".agentscope/bus"));
    }

    @Test
    void compaction_neitherFlushesMemoryNorOffloadsBeforeCompacting() {
        TestAssembly assembly = TestAssembly.builder(model).build();

        HarnessAgent agent = assemble(
                assembly,
                TestSpecs.agent().compaction(new CompactionSpec(32_000)).build());

        CompactionMiddleware hook = agent.getCompactionHook();
        assertThat(hook).isNotNull();
        CompactionConfig config = (CompactionConfig) Reflection.field(hook, "config");
        assertThat(config.isFlushBeforeCompact()).isFalse();
        assertThat(config.isOffloadBeforeCompact()).isFalse();
        assertThat(config.getReserved()).isEqualTo(32_000);
        assertThat(config.getTriggerMessages())
                .as("只按 token 阈值（窗口减 reserved）触发，不按条数")
                .isZero();
        assertThat(config.getTriggerTokens()).as("动态阈值").isZero();
        assertThat(config.getSummaryPrompt()).as("摘要提示词由平台维护").isEqualTo(CompactionPrompt.SUMMARY);
        assertThat(CompactionPrompt.SUMMARY)
                .contains(CompactionPrompt.MESSAGES_PLACEHOLDER)
                .contains("目标", "关键决定", "产物", "未完成事项", "下一步");
        assertThat(Reflection.field(hook, "model")).as("压缩器使用同一个 RoutedModel").isSameAs(model);
    }

    @Test
    void toolResultEviction_offloadsAboveEightyThousandCharsKeepingTwoThousandPreview() {
        HarnessAgent agent =
                assemble(TestAssembly.builder(model).build(), TestSpecs.agent().build());

        ToolResultEvictionMiddleware eviction = middleware(agent, ToolResultEvictionMiddleware.class);
        ToolResultEvictionConfig config = (ToolResultEvictionConfig) Reflection.field(eviction, "config");
        assertThat(config.getMaxResultChars()).isEqualTo(80_000);
        assertThat(config.getPreviewChars()).isEqualTo(2_000);
        assertThat(config.getEvictionPath()).isEqualTo("large_tool_results");
    }

    @Test
    void enablePlanMode_followsTheAgentConfigurationAndIsNeverOnForSubagents() {
        TestAssembly assembly = TestAssembly.builder(model).build();
        ResolvedAgentSpec withPlan = TestSpecs.agent()
                .planMode(true)
                .tools("read_file", "spawn_subagent")
                .subagent(subagent("reader", Set.of("read_file")))
                .build();

        assertThat(assemble(assembly, withPlan).getToolkit().getToolNames())
                .contains("plan_enter", "plan_write", "plan_exit");
        assertThat(assemble(assembly, TestSpecs.agent().build()).getToolkit().getToolNames())
                .doesNotContain("plan_enter", "plan_write", "plan_exit");
        HarnessAgent sub = assembly.assembler()
                .assemble(AgentProfile.subagent(withPlan, "reader", TestSpecs.noneEnvironment()))
                .agent();
        assertThat(sub.getToolkit().getToolNames()).doesNotContain("plan_enter", "plan_write", "plan_exit");
    }

    @Test
    void middlewares_mainAndSubagentReceiveTheirOwnPlatformMiddlewares() {
        MiddlewareBase mainOnly = new NamedMiddleware("main");
        MiddlewareBase subOnly = new NamedMiddleware("sub");
        RecordingMiddlewareProvider provider = new RecordingMiddlewareProvider(List.of(mainOnly), List.of(subOnly));
        TestAssembly assembly =
                TestAssembly.builder(model).middlewares(provider).build();
        ResolvedAgentSpec spec = TestSpecs.agent()
                .tools("read_file", "spawn_subagent")
                .subagent(subagent("reader", Set.of("read_file")))
                .build();

        HarnessAgent main = assemble(assembly, spec);
        HarnessAgent sub = assembly.assembler()
                .assemble(AgentProfile.subagent(spec, "reader", TestSpecs.noneEnvironment()))
                .agent();

        assertThat(main.getDelegate().getMiddlewares()).contains(mainOnly).doesNotContain(subOnly);
        assertThat(sub.getDelegate().getMiddlewares()).contains(subOnly).doesNotContain(mainOnly);
        assertThat(provider.profiles()).extracting(AgentProfile::isSubagent).containsExactly(false, true);
    }

    @Test
    void subagent_usesItsOwnModelPromptToolsAndIterations() {
        ScriptedModel subModel = ScriptedModel.builder().build();
        TestAssembly assembly = TestAssembly.builder(model)
                .tools(new StaticToolProvider(
                        List.of(RecordingTool.echo("read_file"), RecordingTool.echo("web_search")), List.of()))
                .build();
        assembly.modelFactory().register("mdl_sub", subModel);
        ResolvedAgentSpec spec = TestSpecs.agent()
                .tools("read_file", "web_search", "spawn_subagent")
                .subagent(new SubagentSpec(
                        "reader",
                        "只读资料",
                        ModelSpec.of("mdl_sub"),
                        "你只负责阅读。",
                        Set.of("read_file"),
                        List.of(),
                        PermissionPolicy.empty(),
                        7,
                        Duration.ofSeconds(120)))
                .build();

        AssembledAgent sub =
                assembly.assembler().assemble(AgentProfile.subagent(spec, "reader", TestSpecs.noneEnvironment()));

        assertThat(sub.profile().key()).isEqualTo(new AssemblyKey("agv_test", "env_test_none", 1, "reader"));
        assertThat(sub.agent().getName()).isEqualTo("reader");
        assertThat(sub.agent().getModel()).isSameAs(subModel);
        assertThat(sub.agent().getMaxIters()).isEqualTo(7);
        assertThat(sub.agent().getDelegate().getSysPrompt())
                .as("子智能体同样分层：平台基础提示词在前，子智能体的提示词作为智能体层")
                .isEqualTo(PlatformPrompt.compose("", "你只负责阅读。"));
        assertThat(sub.agent().getToolkit().getToolNames())
                .contains("read_file")
                .doesNotContain("web_search", "spawn_subagent");
    }

    @Test
    void reservedToolNames_includePlatformHarnessAndMcpPrefix() {
        TestAssembly assembly = TestAssembly.builder(model)
                .tools(StaticToolProvider.always(RecordingTool.echo("read_file")))
                .build();

        AssembledAgent assembled = assembly.assembler()
                .assemble(AgentProfile.main(TestSpecs.agent().build(), TestSpecs.noneEnvironment()));

        assertThat(assembled.reservedToolNames())
                .contains("read_file", "bash", "spawn_subagent")
                .contains("plan_enter", "plan_write", "plan_exit", "load_skill_through_path", "wait_async_results");
        assertThat(assembled.isReservedToolName("mcp__crm__query")).isTrue();
        assertThat(assembled.isReservedToolName("lookup_order")).isFalse();
    }

    @Test
    void close_closesTheAgentAndThePlatformResources() throws Exception {
        TestAssembly assembly = TestAssembly.builder(model).build();
        AssembledAgent assembled = assembly.assembler()
                .assemble(AgentProfile.main(TestSpecs.agent().build(), TestSpecs.noneEnvironment()));

        assembled.close();

        assertThat(assembly.tools().resources())
                .singleElement()
                .satisfies(r -> assertThat(r.closeCount()).isEqualTo(1));
    }

    private static HarnessAgent assemble(TestAssembly assembly, ResolvedAgentSpec spec) {
        return assemble(assembly, spec, TestSpecs.noneEnvironment());
    }

    private static HarnessAgent assemble(TestAssembly assembly, ResolvedAgentSpec spec, EnvironmentSpec environment) {
        return assembly.assembler()
                .assemble(AgentProfile.main(spec, environment))
                .agent();
    }

    /** 执行一次调用，收集全部事件；以错误结束时最后一个元素是异常。 */
    private static List<Object> run(HarnessAgent agent, String text) {
        RuntimeContext ctx =
                RuntimeContext.builder().sessionId(MAIN_THREAD).userId(USER).build();
        return agent.streamEvents(List.of(new UserMessage(text)), ctx)
                .map(Object.class::cast)
                .onErrorResume(e -> Flux.just(e))
                .collectList()
                .block(Duration.ofSeconds(30));
    }

    private static <T> T middleware(HarnessAgent agent, Class<T> type) {
        return agent.getDelegate().getMiddlewares().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有安装 " + type.getSimpleName()));
    }

    private static io.agentscope.core.tool.Toolkit toolkitWith(AgentTool tool) {
        io.agentscope.core.tool.Toolkit toolkit = new io.agentscope.core.tool.Toolkit();
        toolkit.registerAgentTool(tool);
        return toolkit;
    }

    private static SubagentSpec subagent(String name, Set<String> tools) {
        return new SubagentSpec(
                name,
                "子智能体 " + name,
                ModelSpec.of("mdl_test"),
                "你是子智能体。",
                tools,
                List.of(),
                PermissionPolicy.empty(),
                10,
                Duration.ofSeconds(60));
    }

    /** 只用来区分实例的中间件，全部挂载点沿用默认的透传实现。 */
    private record NamedMiddleware(String name) implements MiddlewareBase {}
}
