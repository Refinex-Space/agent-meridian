package cn.refinex.meridian.engine.agentscope.turn;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.assembly.AgentHandle;
import cn.refinex.meridian.engine.agentscope.assembly.AgentInstanceCache;
import cn.refinex.meridian.engine.agentscope.events.TurnEventMapper;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformStopRequest;
import cn.refinex.meridian.engine.spi.engine.AgentEngine;
import cn.refinex.meridian.engine.spi.engine.ThreadRef;
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
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.shutdown.GracefulShutdownConfig;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.PartialReasoningPolicy;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *   <li>经 {@link TurnEventMapper} 映射事件；结束（含异常、取消）时再次 {@code clearStateCache} 并归还实例。
 * </ol>
 *
 * <p>终态保证：正常结束输出唯一的终态；错误先输出 {@code session.error} 再输出终态（含配置读取、装配、权限安装等
 * 调用之前的错误）；{@code AgentShuttingDownException} 只输出 {@code session.status_rescheduled}；租约丢失不输出任何事件，
 * 以 {@code LeaseLostException} 结束；订阅被取消时不输出事件，也不调用 {@code interrupt}（ADR-0026）。
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

    private final AgentInstanceCache instances;
    private final AgentConfigPort configs;
    private final LeaseRegistry leases;
    private final BlobStorePort blobs;
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
     */
    public AgentScopeEngine(
            AgentInstanceCache instances, AgentConfigPort configs, LeaseRegistry leases, BlobStorePort blobs) {
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
                    TurnSlot slot = new TurnSlot();
                    // usingWhen 在终态之前归还资源；取消发生在 begin 仍在虚拟线程上执行时，usingWhen 拿不到资源，
                    // 由 slot 在 begin 完成后自行清理，避免实例与执行登记泄漏
                    return Flux.usingWhen(
                                    Blocking.call(() -> slot.attach(begin(ctx, input, specs.getT1(), specs.getT2()))),
                                    this::execute,
                                    turn -> end(slot),
                                    (turn, error) -> end(slot),
                                    turn -> end(slot))
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
        AgentHandle handle = instances.acquire(agent, environment);
        RunningTurn turn = new RunningTurn(ctx, input, agent, handle, userId);
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
                                userId, threadId, PermissionTranslator.toContextState(ctx.permissions()));
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

    private Flux<EngineEvent> execute(RunningTurn turn) {
        TurnEventMapper mapper = new TurnEventMapper(turn.mappingSettings(), blobs);
        List<Msg> messages = TurnInputTranslator.messages(turn.input());
        return Flux.defer(() -> turn.agent().streamEvents(messages, turn.runtimeContext()))
                .doOnNext(turn::observe)
                .concatMap(mapper::map)
                .concatWith(Mono.fromSupplier(mapper::complete));
    }

    private Mono<Void> end(TurnSlot slot) {
        return Mono.fromRunnable(slot::close);
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
     * 一轮执行的资源槽：{@link #attach} 与 {@link #close} 无论谁先发生，{@link #finish} 都恰好执行一次。
     *
     * <p>线程安全：两个方法互斥。
     */
    private final class TurnSlot {

        private RunningTurn turn;
        private boolean closed;

        /** 登记准备好的执行；槽已关闭（订阅已被取消）时立即结束它。 */
        RunningTurn attach(RunningTurn prepared) {
            synchronized (this) {
                if (!closed) {
                    turn = prepared;
                    return prepared;
                }
            }
            finish(prepared);
            return prepared;
        }

        /** 关闭槽并结束已登记的执行，可以重复调用。 */
        void close() {
            RunningTurn toFinish;
            synchronized (this) {
                closed = true;
                toFinish = turn;
                turn = null;
            }
            if (toFinish != null) {
                finish(toFinish);
            }
        }
    }

    /**
     * 本节点上正在执行的一轮。
     *
     * <p>中断：AgentScope 只在调用被受理（进入 {@code runningCalls}）之后响应 {@code interrupt}，之前的中断会被静默丢弃。
     * 调用在发出 {@code AGENT_START} 之后才被受理，所以受理前收到的中断，在看到第一个其他事件时再发一次。
     */
    private static final class RunningTurn {

        private final TurnContext ctx;
        private final TurnInput input;
        private final ResolvedAgentSpec spec;
        private final AgentHandle handle;
        private final String userId;
        private final RuntimeContext runtimeContext;
        private final PlatformStopRequest platformStop = new PlatformStopRequest();
        private boolean admitted;
        private boolean interruptBeforeAdmission;

        RunningTurn(TurnContext ctx, TurnInput input, ResolvedAgentSpec spec, AgentHandle handle, String userId) {
            this.ctx = ctx;
            this.input = input;
            this.spec = spec;
            this.handle = handle;
            this.userId = userId;
            RuntimeContext.Builder builder = RuntimeContext.builder()
                    .sessionId(ctx.platform().threadId())
                    .userId(userId)
                    .put(PlatformContext.class, ctx.platform())
                    .put(PlatformStopRequest.class, platformStop);
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
                    platformStop);
        }

        synchronized void interrupt() {
            agent().interrupt(userId, threadId());
            if (!admitted) {
                interruptBeforeAdmission = true;
            }
        }

        synchronized void observe(AgentEvent event) {
            if (admitted || event instanceof AgentStartEvent) {
                return;
            }
            admitted = true;
            if (interruptBeforeAdmission) {
                agent().interrupt(userId, threadId());
            }
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
