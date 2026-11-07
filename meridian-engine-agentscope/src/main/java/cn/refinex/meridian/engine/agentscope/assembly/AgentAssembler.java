package cn.refinex.meridian.engine.agentscope.assembly;

import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把智能体版本加环境装配为 {@link HarnessAgent}，逐行落实架构设计「引擎装配」表，表中每一行在
 * {@link #configure} 中有对应的一段，由 {@code AgentAssemblerTest} 逐项断言。
 *
 * <p>尚未实现的平台组件经构造参数注入：模型工厂（T09a 的 {@code RoutedModel}）、状态存储（T04/T05 的
 * {@code FencedAgentStateStore}）、平台中间件（T04 起）、平台工具（T10a 起）、文件系统（T10a、T12a）、技能仓库（T10a）。
 * 装配器自身无状态，可以并发调用；实例的复用与关闭由 {@link AgentInstanceCache} 负责。
 *
 * <p>与架构设计的差异：{@code maxRetries(0)} 在 AgentScope 2.0.4 中会抛异常（参数含义是含首次在内的总尝试次数），
 * 已提 ADR-0023，确认前不设置该项；另外按 ADR-0024 补充了 {@code asyncToolRegistry} 与 {@code enableAgentTracingLog(false)}。
 */
public final class AgentAssembler {

    private static final Logger log = LoggerFactory.getLogger(AgentAssembler.class);

    /** 工具结果卸载的阈值，单位字符。架构设计「上下文管理」规定 80000。 */
    static final int TOOL_RESULT_EVICTION_THRESHOLD_CHARS = 80_000;

    /** 卸载后留在上下文中的预览长度，单位字符。架构设计「上下文管理」规定 2000。 */
    static final int TOOL_RESULT_PREVIEW_CHARS = 2_000;

    /** 卸载目录，与 {@link WorkspaceFilesystemProvider#LARGE_TOOL_RESULTS_PREFIX} 是同一个路径。 */
    static final String TOOL_RESULT_EVICTION_PATH = "large_tool_results";

    private final ModelFactory modelFactory;
    private final AgentStateStore stateStore;
    private final MiddlewareProvider middlewareProvider;
    private final ToolProvider toolProvider;
    private final WorkspaceFilesystemProvider filesystemProvider;
    private final SkillRepositoryProvider skillRepositoryProvider;

    /**
     * 创建装配器。
     *
     * @param modelFactory 模型工厂
     * @param stateStore 智能体状态存储，所有实例共享；生产为 {@code FencedAgentStateStore}
     * @param middlewareProvider 平台中间件的提供者
     * @param toolProvider 平台工具的提供者
     * @param filesystemProvider 工作区文件系统的提供者
     * @param skillRepositoryProvider 技能仓库的提供者
     */
    public AgentAssembler(
            ModelFactory modelFactory,
            AgentStateStore stateStore,
            MiddlewareProvider middlewareProvider,
            ToolProvider toolProvider,
            WorkspaceFilesystemProvider filesystemProvider,
            SkillRepositoryProvider skillRepositoryProvider) {
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.middlewareProvider = Objects.requireNonNull(middlewareProvider, "middlewareProvider");
        this.toolProvider = Objects.requireNonNull(toolProvider, "toolProvider");
        this.filesystemProvider = Objects.requireNonNull(filesystemProvider, "filesystemProvider");
        this.skillRepositoryProvider = Objects.requireNonNull(skillRepositoryProvider, "skillRepositoryProvider");
    }

    /**
     * 装配一个智能体实例。每次调用都构建新实例，调用方负责在不用时关闭它。
     *
     * @param profile 装配对象（主智能体或子智能体）
     * @return 装配好的实例
     */
    public AssembledAgent assemble(AgentProfile profile) {
        ToolProvider.ToolBundle tools = toolProvider.tools(profile);
        HarnessAgent agent;
        try {
            HarnessAgent.Builder builder = HarnessAgent.builder();
            configure(builder, profile, tools.tools());
            agent = builder.build();
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

    /** 按「引擎装配」表配置 Builder，每一段对应表中的一行。 */
    private void configure(HarnessAgent.Builder builder, AgentProfile profile, List<AgentTool> tools) {
        builder.name(profile.name()).agentId(profile.key().agentId()).sysPrompt(profile.systemPrompt());
        if (profile.isSubagent()) {
            builder.description(profile.subagent().description());
        }

        // model(Model)：平台的 RoutedModel，内含主模型与备用模型；不经 ModelRegistry
        builder.model(modelFactory.create(profile.model()));
        GenerateOptions generateOptions = generateOptions(profile.model());
        if (generateOptions != null) {
            builder.generateOptions(generateOptions);
        }
        // fallbackModel：不设置。AgentScope 对首个信号的任何错误都会切换，降级由 RoutedModel 自己做
        // maxRetries：架构设计写的 maxRetries(0) 在 2.0.4 中非法，见 ADR-0023（提议），确认前不设置

        // stateStore：FencedAgentStateStore，所有状态写入受租约约束
        builder.stateStore(stateStore);
        // enablePendingToolRecovery：悬空的 tool_use 自动补结果
        builder.enablePendingToolRecovery(true);
        // maxIters：智能体配置，默认 50，上限 200（范围在 ResolvedAgentSpec 中校验）
        builder.maxIters(profile.maxIters());

        // 文件系统：none 档 abstractFilesystem(...)；sandbox 档 filesystem(spec) 加 filesystemRoute(...)，两者互斥
        configureFilesystem(builder, profile);

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
                .disableTranscript();

        // messageBus 与 asyncToolRegistry：都用空实现，推理前不读工作区（ADR-0024）
        builder.messageBus(NoopMessageBus.INSTANCE).asyncToolRegistry(NoopAsyncToolRegistry.INSTANCE);
        // AgentTraceMiddleware 以 INFO 记录模型输出正文，违反日志规范，关闭（ADR-0024）
        builder.enableAgentTracingLog(false);

        // skillRepository：平台技能仓库，按租户、项目、智能体解析
        builder.skillRepository(skillRepositoryProvider.repository(profile));

        // compaction：一期没有长期记忆，压缩前不刷写记忆、不卸载原文
        builder.compaction(CompactionConfig.builder()
                .reserved(profile.agent().compaction().reservedTokens())
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

        // 平台工具注册在传入的 Toolkit 上；Toolkit 默认并行执行同一次推理发起的多个工具调用
        Toolkit toolkit = new Toolkit();
        tools.forEach(toolkit::registerAgentTool);
        builder.toolkit(toolkit);
    }

    private void configureFilesystem(HarnessAgent.Builder builder, AgentProfile profile) {
        switch (profile.environment().tier()) {
            case NONE ->
                builder.abstractFilesystem(new CompositeFilesystem(
                        filesystemProvider.platformStore(profile), filesystemProvider.protectedRoutes(profile)));
            case SANDBOX -> {
                // 会话沙箱由平台管理，默认的工作区投影关闭（架构设计「文件系统装配」）
                builder.filesystem(filesystemProvider.sandboxSpec(profile).workspaceProjectionEnabled(false));
                filesystemProvider.protectedRoutes(profile).forEach(builder::filesystemRoute);
                builder.filesystemRoute(
                        WorkspaceFilesystemProvider.LARGE_TOOL_RESULTS_PREFIX,
                        filesystemProvider.largeToolResultStore(profile));
            }
        }
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
