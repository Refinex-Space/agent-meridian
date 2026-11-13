package cn.refinex.meridian.engine.agentscope.turn;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.agentscope.assembly.AgentHandle;
import cn.refinex.meridian.engine.agentscope.assembly.AgentInstanceCache;
import cn.refinex.meridian.engine.agentscope.assembly.PlatformContextMessage;
import cn.refinex.meridian.engine.agentscope.events.TurnEventMapper;
import cn.refinex.meridian.engine.agentscope.middleware.BatchCheckpoint;
import cn.refinex.meridian.engine.agentscope.middleware.PendingInterrupt;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformStopRequest;
import cn.refinex.meridian.engine.agentscope.middleware.RecoveryNoteSlot;
import cn.refinex.meridian.engine.agentscope.middleware.SubagentQuota;
import cn.refinex.meridian.engine.agentscope.sandbox.TurnSandboxes;
import cn.refinex.meridian.engine.spi.engine.AgentEngine;
import cn.refinex.meridian.engine.spi.engine.ThreadRef;
import cn.refinex.meridian.engine.spi.engine.ToolRetry;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.port.AgentConfigPort;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.engine.spi.port.LeaseRegistry;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.shutdown.GracefulShutdownConfig;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.PartialReasoningPolicy;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 基于 AgentScope 2.0.4 {@code HarnessAgent} 的智能体内核：执行一轮、中断、节点下线，见架构设计「内核适配层」。
 *
 * <p>{@link #runTurn} 的执行顺序（T04 交付物 1）：
 * <ol>
 *   <li>经 {@link AgentConfigPort} 取智能体版本与环境配置，从 {@link AgentInstanceCache} 租用实例（结束时归还）。
 *   <li>按「身份映射」构造 {@code RuntimeContext}：{@code sessionId} 为线程 ID，{@code userId} 为
 *       {@code {project_id}:{end_user_id}}，{@code PlatformContext} 以类型键放入；自定义工具经 {@code ToolRequestConfig} 注入。
 *   <li>{@code clearStateCache(userId, threadId)}；把合成后的权限策略翻译为 {@code PermissionContextState}，在
 *       {@link LeaseRegistry} 条目标记"权限安装写入"期间经 {@code getDelegate().replacePermissionContext(...)} 安装，
 *       状态存储据此不更新 {@code content_epoch}。
 *   <li>按输入类型翻译入参，调用 {@code streamEvents}；整条流在顶层 {@code contextWrite} 写入 {@code PlatformContext}。
 *       恢复说明与批次 checkpoint 的标记以类型键放进 {@code RuntimeContext}，由对应的中间件读取（ADR-0028）。
 *   <li>经 {@link TurnEventMapper} 映射事件；结束（含异常、取消）时再次 {@code clearStateCache} 并归还实例。
 * </ol>
 *
 * <p>终态保证：正常结束输出唯一的终态；错误先输出 {@code session.error} 再输出终态（含配置读取、装配、权限安装等
 * 调用之前的错误）；{@code AgentShuttingDownException} 只输出 {@code session.status_rescheduled}；租约丢失不输出任何事件，
 * 以 {@code LeaseLostException} 结束；订阅被取消时不输出事件，也不调用 {@code interrupt}（ADR-0026）。
 *
 * <p>子智能体（T13，架构设计「子智能体」）：配置了 {@link SubagentRunner} 时，每轮把 {@link SubagentQuota}（{@code PolicyMiddleware}
 * 登记拉起名额）与 {@link SubagentSlot}（{@code spawn_subagent} 的入口）放进主线程的 {@code RuntimeContext}；{@link #interrupt}
 * 先中断本轮登记的全部子线程，再中断父线程。
 *
 * <p>同一节点上同一线程同时只能有一轮在执行（架构设计「一轮执行的生命周期」中"同节点重新认领"），重复执行以
 * {@link IllegalStateException} 失败。阻塞的步骤（装配、读写状态）经 {@link Blocking} 在虚拟线程上执行，AgentScope
 * 的调用也在该线程上订阅。
 *
 * <p>线程安全：可被多个 Worker 并发调用。
 */
public final class AgentScopeEngine implements AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeEngine.class);

    /** AgentScope 优雅停机的强制中断超时。架构设计「崩溃恢复」规定 45 秒；AgentScope 默认不超时。 */
    static final Duration SHUTDOWN_INTERRUPT_TIMEOUT = Duration.ofSeconds(45);

    /** 重新执行工具时视为失败的结果状态。 */
    private static final Set<ToolResultState> FAILED_STATES =
            Set.of(ToolResultState.ERROR, ToolResultState.DENIED, ToolResultState.INTERRUPTED);

    private final AgentInstanceCache instances;
    private final AgentConfigPort configs;
    private final LeaseRegistry leases;
    private final BlobStorePort blobs;
    private final Clock clock;
    private final TurnSandboxes sandboxes;
    private final SubagentRunner subagents;
    private final Map<String, RunningTurn> running = new ConcurrentHashMap<>();

    /**
     * 创建内核，并把 AgentScope 优雅停机的超时设为 {@link #SHUTDOWN_INTERRUPT_TIMEOUT}。
     *
     * <p>{@link GracefulShutdownManager} 是 JVM 级单例，AgentScope 自注册的 JVM 关闭钩子与 {@link #shutdown} 并存，
     * 两者的操作都是幂等的。
     *
     * @param instances 智能体实例缓存
     * @param configs 智能体版本与环境配置
     * @param leases 本节点持有的租约，权限安装时在条目上做标记
     * @param blobs 大对象存储，超过 16 KB 的工具结果写入这里
     * @param clock 时钟，平台上下文消息中的当前时间取自它
     */
    public AgentScopeEngine(
            AgentInstanceCache instances,
            AgentConfigPort configs,
            LeaseRegistry leases,
            BlobStorePort blobs,
            Clock clock) {
        this(instances, configs, leases, blobs, clock, null);
    }

    /**
     * 创建支持 {@code sandbox} 档的内核：每轮经 {@link TurnSandboxes} 取得会话沙箱，作为外部沙箱放进 {@code RuntimeContext}
     * （架构设计「会话沙箱」）。
     *
     * @param instances 智能体实例缓存
     * @param configs 智能体版本与环境配置
     * @param leases 本节点持有的租约，权限安装时在条目上做标记
     * @param blobs 大对象存储，超过 16 KB 的工具结果写入这里
     * @param clock 时钟，平台上下文消息中的当前时间取自它
     * @param sandboxes 每轮的会话沙箱；为 null 时不支持 {@code sandbox} 档，这类轮次以错误结束
     */
    public AgentScopeEngine(
            AgentInstanceCache instances,
            AgentConfigPort configs,
            LeaseRegistry leases,
            BlobStorePort blobs,
            Clock clock,
            TurnSandboxes sandboxes) {
        this(instances, configs, leases, blobs, clock, sandboxes, null);
    }

    /**
     * 创建支持 {@code sandbox} 档与子智能体的内核。
     *
     * @param instances 智能体实例缓存
     * @param configs 智能体版本与环境配置
     * @param leases 本节点持有的租约，权限安装时在条目上做标记
     * @param blobs 大对象存储，超过 16 KB 的工具结果写入这里
     * @param clock 时钟，平台上下文消息中的当前时间取自它
     * @param sandboxes 每轮的会话沙箱；为 null 时不支持 {@code sandbox} 档，这类轮次以错误结束
     * @param subagents 子智能体的执行组件；为 null 时 {@code spawn_subagent} 返回"不可用"的错误结果
     */
    public AgentScopeEngine(
            AgentInstanceCache instances,
            AgentConfigPort configs,
            LeaseRegistry leases,
            BlobStorePort blobs,
            Clock clock,
            TurnSandboxes sandboxes,
            SubagentRunner subagents) {
        this.sandboxes = sandboxes;
        this.subagents = subagents;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.instances = Objects.requireNonNull(instances, "instances");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        GracefulShutdownManager.getInstance()
                .setConfig(new GracefulShutdownConfig(SHUTDOWN_INTERRUPT_TIMEOUT, PartialReasoningPolicy.SAVE));
    }

    @Override
    public Flux<EngineEvent> runTurn(TurnContext ctx, TurnInput input) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(input, "input");
        return Mono.zip(
                        configs.agentVersion(ctx.agentVersionId()),
                        configs.environment(ctx.environmentId(), ctx.environmentRevision()))
                .flatMapMany(specs -> {
                    ExecutionSlot<RunningTurn> slot = new ExecutionSlot<>(this::finish);
                    // usingWhen 在终态之前归还资源；取消发生在 begin 仍在虚拟线程上执行时，usingWhen 拿不到资源，
                    // 由 slot 在 begin 完成后自行清理，避免实例与执行登记泄漏
                    return Flux.usingWhen(
                                    Blocking.call(() -> slot.attach(begin(ctx, input, specs.getT1(), specs.getT2()))),
                                    this::execute,
                                    turn -> Mono.fromRunnable(slot::close),
                                    (turn, error) -> Mono.fromRunnable(slot::close),
                                    turn -> Mono.fromRunnable(slot::close))
                            .doOnCancel(slot::close);
                })
                .onErrorResume(TurnEventMapper::fail)
                .contextWrite(PlatformContexts.write(ctx.platform()));
    }

    @Override
    public boolean interrupt(ThreadRef thread) {
        RunningTurn turn = running.get(thread.threadId());
        if (turn == null || !turn.sessionId().equals(thread.sessionId())) {
            return false;
        }
        turn.interrupt();
        log.info("已向本节点的执行发出中断 sessionId={} threadId={}", thread.sessionId(), thread.threadId());
        return true;
    }

    @Override
    public Mono<ToolRetry.Result> retryToolCall(TurnContext ctx, ToolRetry retry) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(retry, "retry");
        return Mono.zip(
                        configs.agentVersion(ctx.agentVersionId()),
                        configs.environment(ctx.environmentId(), ctx.environmentRevision()))
                .flatMap(specs -> Blocking.call(() -> sandbox(ctx.platform(), specs.getT2()))
                        .flatMap(sandbox -> Mono.usingWhen(
                                Blocking.call(() -> instances.acquire(specs.getT1(), specs.getT2())),
                                handle -> callTool(ctx, retry, handle, specs.getT1(), specs.getT2(), sandbox),
                                handle -> Mono.fromRunnable(() -> instances.release(handle)))))
                .onErrorResume(error -> !(error instanceof LeaseLostException), error -> {
                    log.warn("重新执行工具调用失败 toolUseId={} tool={}", retry.toolUseId(), retry.toolName(), error);
                    return Mono.just(new ToolRetry.Result(false, "重新执行失败：" + error.getMessage()));
                })
                .contextWrite(PlatformContexts.write(ctx.platform()));
    }

    @Override
    public Mono<Set<String>> reservedToolNames(String agentVersionId, String environmentId, long environmentRevision) {
        return Mono.zip(configs.agentVersion(agentVersionId), configs.environment(environmentId, environmentRevision))
                .flatMap(specs -> Blocking.call(() -> {
                    AgentHandle handle = instances.acquire(specs.getT1(), specs.getT2());
                    try {
                        return handle.assembled().reservedToolNames();
                    } finally {
                        instances.release(handle);
                    }
                }));
    }

    /**
     * 在智能体实例的 Toolkit 中找到工具并直接调用，不经模型与权限引擎（调用已由人确认重试）。{@code sandbox} 档把会话沙箱作为本次
     * 调用绑定的沙箱放进上下文：直接调用工具不经过 harness 的沙箱生命周期，沙箱文件系统要从上下文取到它。重新执行的是
     * {@code spawn_subagent} 时，放入子智能体的入口，拉起一个新的子线程（不经 {@code PolicyMiddleware}，不占本轮名额）。
     */
    private Mono<ToolRetry.Result> callTool(
            TurnContext ctx,
            ToolRetry retry,
            AgentHandle handle,
            ResolvedAgentSpec agent,
            EnvironmentSpec environment,
            Optional<Sandbox> sandbox) {
        AgentTool tool = handle.agent().getToolkit().getTool(retry.toolName());
        if (tool == null) {
            return Mono.just(new ToolRetry.Result(false, "工具已不存在：" + retry.toolName()));
        }
        PlatformContext platform = ctx.platform();
        String userId = AgentScopeIdentity.userId(platform.projectId(), ctx.endUserId());
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .sessionId(platform.threadId())
                .userId(userId)
                .put(PlatformContext.class, platform)
                .put(ToolRetryContext.class, new ToolRetryContext(retry.idempotencyKey()));
        if (subagents != null) {
            builder.put(
                    SubagentSlot.class,
                    new SubagentSlot(
                            subagents,
                            new SubagentSlot.Parent(ctx, agent, environment, userId, sandbox.orElse(null)),
                            new SubagentQuota()));
        }
        sandbox.ifPresent(s -> builder.put(
                        SandboxContext.class,
                        SandboxContext.builder().externalSandbox(s).build())
                .put(SandboxAcquireResult.class, SandboxAcquireResult.userManaged(s)));
        RuntimeContext runtime = builder.build();
        ToolUseBlock call = new ToolUseBlock(
                retry.toolUseId(),
                retry.toolName(),
                retry.input(),
                JsonUtils.getJsonCodec().toJson(retry.input()),
                null);
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(call)
                .input(retry.input())
                .agent(handle.agent().getDelegate())
                .runtimeContext(runtime)
                .build();
        // 工具直接返回的结果状态默认为 RUNNING（由 2.0.4 的 ToolExecutor 定稿），只有明确的错误、拒绝、中断算失败
        return tool.callAsync(param)
                .map(result -> new ToolRetry.Result(!FAILED_STATES.contains(result.getState()), textOf(result)));
    }

    private static String textOf(ToolResultBlock result) {
        return result.getOutput() == null
                ? ""
                : result.getOutput().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(block -> ((TextBlock) block).getText())
                        .collect(Collectors.joining("\n"));
    }

    @Override
    public void evictAgentVersions(Set<String> agentVersionIds) {
        instances.evictAgentVersions(agentVersionIds);
        log.info("智能体实例已按版本淘汰 agentVersions={}", agentVersionIds.size());
    }

    @Override
    public Mono<Void> shutdown(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        return Blocking.run(() -> {
            GracefulShutdownManager manager = GracefulShutdownManager.getInstance();
            log.info("节点下线：中断全部执行并等待状态保存 running={} timeout={}", running.size(), timeout);
            manager.performGracefulShutdown();
            if (!manager.awaitTermination(timeout)) {
                log.warn("节点下线等待超时，仍有执行未结束 running={}", running.size());
            }
        });
    }

    /** 租用实例、登记执行、清理缓存并安装权限；任何一步失败都归还已租用的资源。在虚拟线程上执行。 */
    private RunningTurn begin(TurnContext ctx, TurnInput input, ResolvedAgentSpec agent, EnvironmentSpec environment) {
        PlatformContext platform = ctx.platform();
        String threadId = platform.threadId();
        String userId = AgentScopeIdentity.userId(platform.projectId(), ctx.endUserId());
        // sandbox 档：会话沙箱作为外部沙箱放进本轮的 RuntimeContext，harness 不停止也不持久化它（架构设计「会话沙箱」）
        Sandbox sandbox = sandbox(platform, environment).orElse(null);
        AgentHandle handle = instances.acquire(agent, environment);
        RunningTurn turn;
        try {
            SubagentSlot.Parent parent = new SubagentSlot.Parent(ctx, agent, environment, userId, sandbox);
            turn = new RunningTurn(ctx, input, parent, handle, subagents);
        } catch (RuntimeException e) {
            // 构造失败（例如自定义工具声明不合法）时归还实例，否则引用计数永不归零，淘汰后也不会关闭（R1 审查 A-F5）
            instances.release(handle);
            throw e;
        }
        if (running.putIfAbsent(threadId, turn) != null) {
            instances.release(handle);
            throw new IllegalStateException("线程已在本节点执行，上一次执行结束前不能再次认领：" + threadId);
        }
        try {
            HarnessAgent harness = handle.agent();
            harness.clearStateCache(userId, threadId);
            leases.markPermissionInstall(threadId, true);
            try {
                harness.getDelegate()
                        .replacePermissionContext(
                                userId,
                                threadId,
                                PermissionTranslator.toContextState(
                                        ctx.permissions(), handle.assembled().policyDenyRules()));
            } finally {
                leases.markPermissionInstall(threadId, false);
            }
            return turn;
        } catch (RuntimeException e) {
            running.remove(threadId, turn);
            harnessClear(turn);
            instances.release(handle);
            throw e;
        }
    }

    /** {@code sandbox} 档本轮的会话沙箱；{@code none} 档为空。会阻塞，在虚拟线程上调用。 */
    private Optional<Sandbox> sandbox(PlatformContext platform, EnvironmentSpec environment) {
        if (environment.tier() != EnvironmentSpec.Tier.SANDBOX) {
            return Optional.empty();
        }
        if (sandboxes == null) {
            throw new IllegalStateException("内核没有配置会话沙箱，不能执行 sandbox 档的轮次：" + environment.environmentId());
        }
        return Optional.of(sandboxes.sandbox(platform));
    }

    private Flux<EngineEvent> execute(RunningTurn turn) {
        TurnEventMapper mapper = new TurnEventMapper(turn.mappingSettings(), blobs);
        List<Msg> messages = withPlatformContext(
                turn.input(), TurnInputTranslator.messages(turn.input(), turn.ctx.workspaceFiles()));
        return Flux.defer(() -> turn.agent().streamEvents(messages, turn.runtimeContext()))
                .doOnNext(turn::observe)
                .concatMap(mapper::map)
                .concatWith(Mono.fromSupplier(mapper::complete));
    }

    /**
     * 以新消息开始的轮次，在用户消息之前注入平台上下文（当前时间），时间不进系统提示词（ADR-0035）。审批结果、自定义工具结果与恢复轮
     * 不注入：前两者不能与普通消息混在同一轮（架构设计「内核适配层」），恢复轮没有新的用户输入。
     */
    private List<Msg> withPlatformContext(TurnInput input, List<Msg> messages) {
        if (!(input instanceof TurnInput.NewMessages)) {
            return messages;
        }
        List<Msg> all = new ArrayList<>(messages.size() + 1);
        all.add(PlatformContextMessage.of(clock.instant()));
        all.addAll(messages);
        return all;
    }

    /** 结束一轮：注销执行、清理状态缓存、归还实例。只清理本机内存，不写任何数据，所以取消时也可以执行。 */
    private void finish(RunningTurn turn) {
        try {
            running.remove(turn.threadId(), turn);
            harnessClear(turn);
        } finally {
            instances.release(turn.handle());
        }
    }

    private static void harnessClear(RunningTurn turn) {
        turn.agent().clearStateCache(turn.userId(), turn.threadId());
    }

    /**
     * 本节点上正在执行的一轮。中断经 {@link InterruptRelay} 处理受理前到达的情况；配置了子智能体执行时，先中断本轮登记的子线程。
     */
    private static final class RunningTurn {

        private final TurnContext ctx;
        private final TurnInput input;
        private final ResolvedAgentSpec spec;
        private final AgentHandle handle;
        private final String userId;
        private final RuntimeContext runtimeContext;
        private final PlatformStopRequest platformStop = new PlatformStopRequest();
        private final InterruptRelay relay;
        private final SubagentSlot subagentSlot;

        RunningTurn(
                TurnContext ctx,
                TurnInput input,
                SubagentSlot.Parent parent,
                AgentHandle handle,
                SubagentRunner subagents) {
            this.ctx = ctx;
            this.input = input;
            this.spec = parent.agent();
            this.handle = handle;
            this.userId = parent.userId();
            this.relay =
                    new InterruptRelay(handle.agent(), userId, ctx.platform().threadId());
            SubagentQuota quota = new SubagentQuota();
            this.subagentSlot = subagents == null ? null : new SubagentSlot(subagents, parent, quota);
            RuntimeContext.Builder builder = RuntimeContext.builder()
                    .sessionId(ctx.platform().threadId())
                    .userId(userId)
                    .put(PlatformContext.class, ctx.platform())
                    .put(PlatformStopRequest.class, platformStop)
                    .put(BatchCheckpoint.class, new BatchCheckpoint())
                    // PolicyMiddleware 在每批工具执行前登记 spawn_subagent 的名额（架构设计「子智能体」）
                    .put(SubagentQuota.class, quota);
            if (subagentSlot != null) {
                builder.put(SubagentSlot.class, subagentSlot);
            }
            if (input.recovery() != null) {
                // 恢复说明由 RecoveryNoteMiddleware 在第一次推理前注入，不走调用入参（架构设计「崩溃恢复」）
                builder.put(RecoveryNoteSlot.class, new RecoveryNoteSlot(input.recovery()));
            }
            if (ctx.interruptRequested()) {
                // 认领时已有中断请求：输入写进上下文后，在第一次推理前按用户中断结束（InterruptCheckpointMiddleware）
                builder.put(PendingInterrupt.class, new PendingInterrupt());
            }
            if (parent.sandbox() != null) {
                // sandbox 档：会话沙箱作为外部沙箱放进本轮的 RuntimeContext，harness 不停止也不持久化它（架构设计「会话沙箱」）
                builder.put(
                        SandboxContext.class,
                        SandboxContext.builder()
                                .externalSandbox(parent.sandbox())
                                .build());
            }
            ToolRequestConfig customTools = TurnInputTranslator.toolRequestConfig(ctx.customTools());
            if (customTools != null) {
                builder.toolRequestConfig(customTools);
            }
            this.runtimeContext = builder.build();
        }

        TurnEventMapper.Settings mappingSettings() {
            return new TurnEventMapper.Settings(
                    spec.model().modelId(),
                    spec.maxIters(),
                    spec.exposeThinking(),
                    ctx.customTools().stream().map(CustomToolDeclaration::name).collect(Collectors.toSet()),
                    TurnInputTranslator.approvedToolUseIds(input),
                    platformStop,
                    this::suggestRules);
        }

        /**
         * 本实例中的工具对一次调用给出的建议规则内容（D21）：只取针对这个工具、有内容的规则，去重；工具不在 Toolkit 中或不是
         * {@code ToolBase} 时没有建议。
         */
        private List<String> suggestRules(String toolName, Map<String, Object> toolInput) {
            AgentTool tool = handle.agent().getToolkit().getTool(toolName);
            if (!(tool instanceof ToolBase base)) {
                return List.of();
            }
            List<PermissionRule> rules = base.generateSuggestions(toolInput);
            if (rules == null) {
                return List.of();
            }
            return rules.stream()
                    .filter(rule -> toolName.equals(rule.toolName()))
                    .map(PermissionRule::ruleContent)
                    .filter(content -> content != null && !content.isBlank())
                    .distinct()
                    .toList();
        }

        /** 先中断本轮的子线程，再中断父线程：{@code spawn_subagent} 是工具，父线程被中断后它不会停（架构设计「消息投递」）。 */
        void interrupt() {
            if (subagentSlot != null) {
                subagentSlot.interruptAll();
            }
            relay.interrupt();
        }

        void observe(AgentEvent event) {
            relay.observe(event);
        }

        HarnessAgent agent() {
            return handle.agent();
        }

        AgentHandle handle() {
            return handle;
        }

        TurnInput input() {
            return input;
        }

        RuntimeContext runtimeContext() {
            return runtimeContext;
        }

        String userId() {
            return userId;
        }

        String threadId() {
            return ctx.platform().threadId();
        }

        String sessionId() {
            return ctx.platform().sessionId();
        }
    }
}
