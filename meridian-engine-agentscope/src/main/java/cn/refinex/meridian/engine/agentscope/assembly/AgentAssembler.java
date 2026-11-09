package cn.refinex.meridian.engine.agentscope.assembly;

import cn.refinex.meridian.engine.agentscope.fs.SandboxWorkspaceFilesystem;
import cn.refinex.meridian.engine.agentscope.tools.PlatformTool;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 把智能体版本加环境装配为 {@link HarnessAgent}，逐行落实架构设计「引擎装配」表，表中每一行在
 * {@link #configure} 中有对应的一段，由 {@code AgentAssemblerTest} 逐项断言。
 *
 * <p>平台组件经构造参数注入：模型工厂（{@code RoutedModelFactory}）、状态存储（{@code FencedAgentStateStore}）、平台中间件、
 * 平台工具、两个档位的文件系统与技能仓库，测试换成 testkit 的替身。装配器自身无状态，可以并发调用；实例的复用与关闭由
 * {@link AgentInstanceCache} 负责。
 *
 * <p>AgentScope 2.0.4 的 {@code maxRetries} 是含首次在内的总尝试次数，不重试写作 {@code maxRetries(1)}（ADR-0023）；
 * {@code asyncToolRegistry} 与 {@code enableAgentTracingLog(false)} 两项按 ADR-0024 补充。
 */
public final class AgentAssembler {

    private static final Logger log = LoggerFactory.getLogger(AgentAssembler.class);

    /** 模型调用的总尝试次数（含首次）。架构设计「引擎装配」与 ADR-0023 规定为 1：重试只由 RoutedModel 进行。 */
    static final int MODEL_MAX_ATTEMPTS = 1;

    /** 工具结果卸载的阈值，单位字符。架构设计「上下文管理」规定 80000。 */
    static final int TOOL_RESULT_EVICTION_THRESHOLD_CHARS = 80_000;

    /** 卸载后留在上下文中的预览长度，单位字符。架构设计「上下文管理」规定 2000。 */
    static final int TOOL_RESULT_PREVIEW_CHARS = 2_000;

    /** 按消息条数触发压缩的阈值；0 表示关闭，只按 token 阈值触发（架构设计「上下文管理」：阈值等于窗口减去 reserved）。 */
    static final int COMPACTION_TRIGGER_MESSAGES = 0;

    /**
     * 单个工具调用的超时。2.0.4 的 Toolkit 默认 5 分钟（{@code ExecutionConfig.TOOL_DEFAULTS}），会截断 {@code bash} 与
     * {@code spawn_subagent} 各自 600 秒的上限；平台工具自己执行超时，这里只是兜底，取 600 秒加 60 秒余量（ADR-0041）。
     */
    static final Duration TOOL_EXECUTION_TIMEOUT = Duration.ofSeconds(660);

    /** 卸载目录，与 {@link WorkspaceFilesystemProvider#LARGE_TOOL_RESULTS_PREFIX} 是同一个路径。 */
    static final String TOOL_RESULT_EVICTION_PATH = "large_tool_results";

    private final ModelFactory modelFactory;
    private final AgentStateStore stateStore;
    private final MiddlewareProvider middlewareProvider;
    private final ToolProvider toolProvider;
    private final WorkspaceFilesystemProvider filesystemProvider;
    private final SkillRepositoryProvider skillRepositoryProvider;
    private final TenantPromptProvider tenantPromptProvider;

    /**
     * 创建装配器。
     *
     * @param modelFactory 模型工厂
     * @param stateStore 智能体状态存储，所有实例共享；生产为 {@code FencedAgentStateStore}
     * @param middlewareProvider 平台中间件的提供者
     * @param toolProvider 平台工具的提供者
     * @param filesystemProvider 工作区文件系统的提供者
     * @param skillRepositoryProvider 技能仓库的提供者
     * @param tenantPromptProvider 系统提示词租户层的提供者
     */
    public AgentAssembler(
            ModelFactory modelFactory,
            AgentStateStore stateStore,
            MiddlewareProvider middlewareProvider,
            ToolProvider toolProvider,
            WorkspaceFilesystemProvider filesystemProvider,
            SkillRepositoryProvider skillRepositoryProvider,
            TenantPromptProvider tenantPromptProvider) {
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.middlewareProvider = Objects.requireNonNull(middlewareProvider, "middlewareProvider");
        this.toolProvider = Objects.requireNonNull(toolProvider, "toolProvider");
        this.filesystemProvider = Objects.requireNonNull(filesystemProvider, "filesystemProvider");
        this.skillRepositoryProvider = Objects.requireNonNull(skillRepositoryProvider, "skillRepositoryProvider");
        this.tenantPromptProvider = Objects.requireNonNull(tenantPromptProvider, "tenantPromptProvider");
    }

    /**
     * 装配一个智能体实例。每次调用都构建新实例，调用方负责在不用时关闭它。
     *
     * @param profile 装配对象（主智能体或子智能体）
     * @return 装配好的实例
     */
    public AssembledAgent assemble(AgentProfile profile) {
        return assemble(
                profile,
                modelFactory.create(profile.key().agentVersionId(), profile.model()),
                tenantPromptProvider.tenantPrompt(profile));
    }

    /**
     * 启动自检（登记事项 D02）：用探测对象走一遍完整装配并立即关闭，Toolkit 中出现白名单外的非 {@code ToolBase} 工具时抛出异常。
     * 探测装配不经模型工厂，使用一个不能调用的占位模型，所以不需要模型目录中存在探测对象的模型；探测时没有租户，不读租户提示词。
     *
     * @param probe 探测对象，通常启用全部平台工具与计划模式
     * @throws IllegalStateException 自检失败时
     */
    public void selfCheck(AgentProfile probe) {
        AssembledAgent assembled = assemble(probe, new ProbeModel(), "");
        try {
            assembled.close();
        } catch (Exception e) {
            log.warn("关闭自检用的智能体实例失败", e);
        }
    }

    private AssembledAgent assemble(AgentProfile profile, Model model, String tenantPrompt) {
        ToolProvider.ToolBundle tools = toolProvider.tools(profile);
        HarnessAgent agent;
        try {
            HarnessAgent.Builder builder = HarnessAgent.builder();
            Map<String, AbstractFilesystem> sandboxRoutes =
                    configure(builder, profile, model, tenantPrompt, tools.tools());
            agent = builder.build();
            // 自检：非 ToolBase 工具不进权限引擎，只允许 harness 的白名单工具（登记事项 D02）
            ToolPolicy.verify(agent.getToolkit());
            // 平台工具经本实例的工作区文件系统操作文件与命令（架构设计「平台工具」）
            AbstractFilesystem workspace = toolFilesystem(agent, sandboxRoutes);
            tools.tools().stream()
                    .filter(PlatformTool.class::isInstance)
                    .map(PlatformTool.class::cast)
                    .forEach(tool -> tool.attach(agent, workspace));
        } catch (RuntimeException e) {
            closeQuietly(tools.resources(), e);
            throw e;
        }
        log.info(
                "智能体实例已装配 agentId={} tier={} tools={}",
                profile.key().agentId(),
                profile.environment().tier().value(),
                agent.getToolkit().getToolNames().size());
        return new AssembledAgent(profile, agent, tools.resources());
    }

    /**
     * 平台工具使用的文件系统。{@code none} 档就是实例工作区管理器的文件系统；{@code sandbox} 档以同一组路由重建
     * {@code RoutedSandboxFilesystem}，主文件系统外包 {@link SandboxWorkspaceFilesystem}：2.0.4 的沙箱文件系统把路径原样交给
     * shell，平台工具的 {@code /x} 路径要翻译到 {@code /workspace} 下，且每次调用必须带本次绑定的会话沙箱（ADR-0037）。
     */
    private static AbstractFilesystem toolFilesystem(
            HarnessAgent agent, Map<String, AbstractFilesystem> sandboxRoutes) {
        AbstractFilesystem filesystem = agent.getWorkspaceManager().getFilesystem();
        if (sandboxRoutes == null) {
            return filesystem;
        }
        if (!(filesystem instanceof RoutedSandboxFilesystem routed)) {
            throw new IllegalStateException("sandbox 档的工作区文件系统应为 RoutedSandboxFilesystem：" + filesystem.getClass());
        }
        return new RoutedSandboxFilesystem(new SandboxWorkspaceFilesystem(routed.primary()), sandboxRoutes);
    }

    /**
     * 按「引擎装配」表配置 Builder，每一段对应表中的一行。
     *
     * @return {@code sandbox} 档挂载的路由（受保护路径与 {@code large_tool_results/}）；{@code none} 档为 null
     */
    private Map<String, AbstractFilesystem> configure(
            HarnessAgent.Builder builder,
            AgentProfile profile,
            Model model,
            String tenantPrompt,
            List<AgentTool> tools) {
        // 系统提示词分层：平台基础提示词 → 租户 → 智能体，技能清单由 HarnessSkillMiddleware 追加在最后（ADR-0035）
        builder.name(profile.name())
                .agentId(profile.key().agentId())
                .sysPrompt(PlatformPrompt.compose(tenantPrompt, profile.systemPrompt()));
        if (profile.isSubagent()) {
            builder.description(profile.subagent().description());
        }

        // model(Model)：平台的 RoutedModel，内含主模型与备用模型；不经 ModelRegistry
        builder.model(model);
        GenerateOptions generateOptions = generateOptions(profile.model());
        if (generateOptions != null) {
            builder.generateOptions(generateOptions);
        }
        // fallbackModel：不设置。AgentScope 对首个信号的任何错误都会切换，降级由 RoutedModel 自己做
        // maxRetries(1)：只尝试一次，AgentScope 侧不重试；它经 GenerateOptions.executionConfig.maxAttempts 传给模型（ADR-0023）
        builder.maxRetries(MODEL_MAX_ATTEMPTS);

        // stateStore：FencedAgentStateStore，所有状态写入受租约约束
        builder.stateStore(stateStore);
        // enablePendingToolRecovery：悬空的 tool_use 自动补结果
        builder.enablePendingToolRecovery(true);
        // maxIters：智能体配置，默认 50，上限 200（范围在 ResolvedAgentSpec 中校验）
        builder.maxIters(profile.maxIters());

        // 文件系统：none 档 abstractFilesystem(...)；sandbox 档 filesystem(spec) 加 filesystemRoute(...)，两者互斥
        Map<String, AbstractFilesystem> sandboxRoutes = configureFilesystem(builder, profile);
        // 本机工作区根由平台控制，不取 ${user.dir}/.agentscope/workspace：harness 会从中读第 3 层技能与本机回退文件（D05）
        builder.workspace(filesystemProvider.localWorkspaceRoot());

        // 内置工具一律关闭，由平台工具替代
        builder.disableWebTools()
                .disableToolsConfig()
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableDefaultWorkspaceSkills()
                .disableDynamicSkills()
                .disableTranscript()
                // 工作区上下文中间件会把 AGENTS.md（智能体层提示词）再注入一次，并带上当天日期与本机路径，破坏提示词分层与缓存前缀
                // （登记事项 D29，ADR-0035）；时间改由平台以消息注入
                .disableWorkspaceContext()
                // 用户消息中的 @路径 会被展开为文件内容：读取不经权限引擎、账本与审计，sandbox 档能读到沙箱内的任意文件（ADR-0041）
                .disableAtPathExpansion();

        // messageBus 与 asyncToolRegistry：都用空实现，推理前不读工作区（ADR-0024）
        builder.messageBus(NoopMessageBus.INSTANCE).asyncToolRegistry(NoopAsyncToolRegistry.INSTANCE);
        // AgentTraceMiddleware 以 INFO 记录模型输出正文，违反日志规范，关闭（ADR-0024）
        builder.enableAgentTracingLog(false);

        // skillRepository：平台技能仓库，按租户、项目、智能体解析
        builder.skillRepository(skillRepositoryProvider.repository(profile));

        // compaction：阈值只按 token（窗口减 reserved，关掉 2.0.4 默认的 50 条消息触发）；摘要提示词由平台维护；
        // 一期没有长期记忆，压缩前不刷写记忆、不卸载原文（架构设计「上下文管理」）
        builder.compaction(CompactionConfig.builder()
                .reserved(profile.agent().compaction().reservedTokens())
                .triggerMessages(COMPACTION_TRIGGER_MESSAGES)
                .summaryPrompt(CompactionPrompt.SUMMARY)
                .flushBeforeCompact(false)
                .offloadBeforeCompact(false)
                .build());
        // toolResultEviction：阈值 80000 字符，预览 2000 字符，写入 large_tool_results/
        builder.toolResultEviction(ToolResultEvictionConfig.builder()
                .maxResultChars(TOOL_RESULT_EVICTION_THRESHOLD_CHARS)
                .previewChars(TOOL_RESULT_PREVIEW_CHARS)
                .evictionPath(TOOL_RESULT_EVICTION_PATH)
                .build());

        // enablePlanMode：按智能体配置；子智能体不启用
        builder.enablePlanMode(profile.planMode());

        // middlewares：平台中间件
        builder.middlewares(middlewareProvider.middlewares(profile));

        // toolExecutionConfig：单个工具的超时放宽到平台工具自己的上限之上，只尝试一次（ADR-0041）
        builder.toolExecutionConfig(ExecutionConfig.builder()
                .timeout(TOOL_EXECUTION_TIMEOUT)
                .maxAttempts(1)
                .build());

        // 平台工具注册在传入的 Toolkit 上；Toolkit 默认并行执行同一次推理发起的多个工具调用
        Toolkit toolkit = new Toolkit();
        tools.forEach(toolkit::registerAgentTool);
        builder.toolkit(toolkit);
        return sandboxRoutes;
    }

    private Map<String, AbstractFilesystem> configureFilesystem(HarnessAgent.Builder builder, AgentProfile profile) {
        return switch (profile.environment().tier()) {
            case NONE -> {
                builder.abstractFilesystem(new CompositeFilesystem(
                        filesystemProvider.platformStore(profile), filesystemProvider.protectedRoutes(profile)));
                yield null;
            }
            case SANDBOX -> {
                // 会话沙箱由平台管理（每轮作为外部沙箱放进 RuntimeContext），默认的工作区投影关闭（架构设计「文件系统装配」）
                builder.filesystem(filesystemProvider.sandboxSpec(profile).workspaceProjectionEnabled(false));
                Map<String, AbstractFilesystem> routes =
                        new LinkedHashMap<>(filesystemProvider.protectedRoutes(profile));
                routes.put(
                        WorkspaceFilesystemProvider.LARGE_TOOL_RESULTS_PREFIX,
                        filesystemProvider.largeToolResultStore(profile));
                routes.forEach(builder::filesystemRoute);
                yield routes;
            }
        };
    }

    /** 把模型引用中的生成参数翻译为 GenerateOptions；都没有设置时返回 null，沿用模型默认值。 */
    private static GenerateOptions generateOptions(ModelSpec model) {
        if (model.maxOutputTokens() == null && model.temperature() == null) {
            return null;
        }
        return GenerateOptions.builder()
                .maxTokens(model.maxOutputTokens())
                .temperature(model.temperature())
                .build();
    }

    /** 自检装配用的占位模型：自检不执行推理，被调用说明装配之外有人用了它。 */
    private static final class ProbeModel implements Model {

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new IllegalStateException("自检装配的智能体不能执行推理"));
        }

        @Override
        public String getModelName() {
            return "meridian-self-check";
        }
    }

    private static void closeQuietly(List<AutoCloseable> resources, RuntimeException cause) {
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                cause.addSuppressed(e);
            }
        }
    }
}
