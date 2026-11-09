package cn.refinex.meridian.engine.agentscope.turn;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.assembly.AgentHandle;
import cn.refinex.meridian.engine.agentscope.assembly.AgentInstanceCache;
import cn.refinex.meridian.engine.agentscope.assembly.PlatformContextMessage;
import cn.refinex.meridian.engine.agentscope.events.TurnEventMapper;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformStopRequest;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.engine.spi.port.EventSinkPort;
import cn.refinex.meridian.engine.spi.port.LeaseRegistry;
import cn.refinex.meridian.engine.spi.port.ThreadRegistryPort;
import cn.refinex.meridian.engine.spi.port.ThreadRegistryPort.ThreadStatus;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.SubagentSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 执行子智能体：{@code spawn_subagent} 经 {@link SubagentSlot#spawn} 调到这里，按架构设计「子智能体」第 2 至 6 步同步执行一次。
 *
 * <ol>
 *   <li>经 {@link ThreadRegistryPort} 创建子线程（同一事务写 {@code session.thread_created}），在 {@link LeaseRegistry} 中把子线程
 *       登记到父线程的租约下：子线程的状态写入使用父轮次的租约。
 *   <li>子线程的 {@code RuntimeContext} 用 {@code RuntimeContext.builder()} 从空白构建，只放派生的 {@code PlatformContext}（线程 ID
 *       改为子线程）、会话沙箱的 {@code SandboxContext} 与本次执行新建的停止记录。<b>禁止</b> {@code builder(parent)}：2.0.4 的
 *       {@code Builder.from} 会复制父线程的 {@code AgentState}、{@code ToolRequestConfig}（自定义工具）与全部类型化属性（沙箱获取
 *       结果、文件系统等）。
 *   <li>子智能体的实例由 {@link AgentInstanceCache#acquireSubagent} 按子智能体配置装配与缓存，挂载子智能体的中间件集合。
 *   <li>与主线程的一轮相同：开始前 {@code clearStateCache}，在权限安装标记下安装 {@code TurnContext.subagentPolicies} 中预先合成
 *       的策略（并入实例的策略拒绝规则），结束时（含异常、取消）再清理一次。缺少该子智能体的策略时失败关闭：{@code DONT_ASK} 且
 *       没有放行规则。
 *   <li>调用 {@code streamEvents}，用一个新的 {@link TurnEventMapper} 映射事件，经 {@link EventSinkPort} 按顺序写成子线程的事件，
 *       前后写 {@code session.thread_status_running} 与 {@code session.thread_status_idle}（经 {@link ThreadRegistryPort}）。子线程
 *       的终态事件不写：主线程的 {@code session.status_*} 会推进会话状态，子线程的结束只体现在 {@code thread_status_idle} 上；子线程的
 *       {@code session.error} 照写，重试状态一律为 {@code terminal}（子线程不会被重试）。
 *   <li>超时取子智能体配置（不超过 600 秒）：到时取消对 {@code streamEvents} 的订阅（AgentScope 不保存状态、不发事件），已经映射的
 *       事件写完后结束子线程（{@code interrupted}）并返回超时结果。
 * </ol>
 *
 * <p>整个执行带派生的 {@code PlatformContext}（Reactor Context）：子智能体的模型调用经 {@code RoutedModel} 计入父会话、用量记录带
 * 子线程 ID，子线程上的工具账本、事件写入都以父轮次的租约为条件。父线程的订阅被取消（失去租约）时，取消一路传到子智能体，不写任何
 * 数据。
 *
 * <p>线程安全：无状态，可被多个轮次与并行的 {@code spawn_subagent} 调用并发使用。
 */
public final class SubagentRunner {

    private static final Logger log = LoggerFactory.getLogger(SubagentRunner.class);

    /** 缺少子智能体的预合成策略时使用的策略：没有人能审批，也没有放行规则，全部工具调用被拒绝。 */
    static final PermissionPolicy FAIL_CLOSED = new PermissionPolicy(PermissionMode.DONT_ASK, List.of());

    private final AgentInstanceCache instances;
    private final LeaseRegistry leases;
    private final ThreadRegistryPort threads;
    private final EventSinkPort events;
    private final BlobStorePort blobs;
    private final Clock clock;

    /**
     * 创建子智能体的执行组件。
     *
     * @param instances 智能体实例缓存，子智能体的实例按父智能体版本加子智能体名缓存
     * @param leases 本节点持有的租约
     * @param threads 子线程的创建与状态
     * @param events 子线程事件的写入
     * @param blobs 大对象存储，超过 16 KB 的工具结果写入这里
     * @param clock 时钟，子智能体任务前的平台上下文消息中的当前时间取自它
     */
    public SubagentRunner(
            AgentInstanceCache instances,
            LeaseRegistry leases,
            ThreadRegistryPort threads,
            EventSinkPort events,
            BlobStorePort blobs,
            Clock clock) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.events = Objects.requireNonNull(events, "events");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 执行一次子智能体，见类注释。 */
    Mono<SubagentOutcome> run(SubagentSlot slot, SubagentSpec subagent, String task, String parentToolUseId) {
        PlatformContext parentPlatform = slot.parent().turn().platform();
        return threads.createChildThread(parentPlatform.threadId(), parentToolUseId, subagent.name())
                .flatMap(childThreadId -> {
                    PlatformContext child =
                            parentPlatform.toBuilder().threadId(childThreadId).build();
                    log.info(
                            "拉起子智能体 subagent={} parentThreadId={} childThreadId={} toolUseId={}",
                            subagent.name(),
                            parentPlatform.threadId(),
                            childThreadId,
                            parentToolUseId);
                    return execute(slot, subagent, task, child).contextWrite(PlatformContexts.write(child));
                });
    }

    private Mono<SubagentOutcome> execute(
            SubagentSlot slot, SubagentSpec subagent, String task, PlatformContext child) {
        ExecutionSlot<ChildRun> resources = new ExecutionSlot<>(this::finish);
        return Mono.usingWhen(
                        Blocking.call(() -> resources.attach(begin(slot, subagent, child))),
                        run -> stream(run, task),
                        run -> Mono.fromRunnable(resources::close),
                        (run, error) -> Mono.fromRunnable(resources::close),
                        run -> Mono.fromRunnable(resources::close))
                .doOnCancel(resources::close);
    }

    /** 租用实例、登记子线程与中断、清理缓存并安装权限；任何一步失败都归还已租用的资源。在虚拟线程上执行。 */
    private ChildRun begin(SubagentSlot slot, SubagentSpec subagent, PlatformContext child) {
        SubagentSlot.Parent parent = slot.parent();
        String childThreadId = child.threadId();
        AgentHandle handle = instances.acquireSubagent(parent.agent(), subagent.name(), parent.environment());
        ChildRun run = new ChildRun(slot, subagent, handle, parent.userId(), child, parent);
        boolean attached = false;
        try {
            leases.attachChildThread(childThreadId, parent.turn().platform().threadId());
            attached = true;
            HarnessAgent agent = handle.agent();
            agent.clearStateCache(parent.userId(), childThreadId);
            PermissionPolicy policy = parent.turn().subagentPolicies().get(subagent.name());
            if (policy == null) {
                log.warn("本轮没有子智能体的预合成权限策略，按全部拒绝执行 subagent={}", subagent.name());
                policy = FAIL_CLOSED;
            }
            leases.markPermissionInstall(childThreadId, true);
            try {
                agent.getDelegate()
                        .replacePermissionContext(
                                parent.userId(),
                                childThreadId,
                                PermissionTranslator.toContextState(
                                        policy, handle.assembled().policyDenyRules()));
            } finally {
                leases.markPermissionInstall(childThreadId, false);
            }
            slot.register(run.relay());
            return run;
        } catch (RuntimeException e) {
            handle.agent().clearStateCache(parent.userId(), childThreadId);
            if (attached) {
                leases.detachChildThread(childThreadId);
            }
            instances.release(handle);
            throw e;
        }
    }

    /** 结束一次执行：取消登记、清理状态缓存、解除子线程的租约登记、归还实例。只清理本机内存，取消时也可以执行。 */
    private void finish(ChildRun run) {
        try {
            run.slot().unregister(run.relay());
            run.agent().clearStateCache(run.userId(), run.threadId());
            leases.detachChildThread(run.threadId());
        } finally {
            instances.release(run.handle());
        }
    }

    private Mono<SubagentOutcome> stream(ChildRun run, String task) {
        SubagentSpec subagent = run.subagent();
        TurnEventMapper mapper = new TurnEventMapper(
                new TurnEventMapper.Settings(
                        subagent.model().modelId(),
                        subagent.maxIters(),
                        run.parent().agent().exposeThinking(),
                        Set.of(),
                        Set.of(),
                        run.stop()),
                blobs);
        AtomicBoolean timedOut = new AtomicBoolean();
        List<Msg> input = List.of(PlatformContextMessage.of(clock.instant()), taskMessage(task));
        Flux<AgentEvent> source = Flux.defer(() -> run.agent().streamEvents(input, run.runtimeContext()))
                .doOnNext(run::observe)
                .takeUntilOther(Mono.delay(subagent.timeout()).doOnNext(tick -> timedOut.set(true)));
        Flux<EngineEvent> mapped = source.concatMap(mapper::map)
                .concatWith(Mono.defer(() -> timedOut.get() ? Mono.empty() : Mono.fromSupplier(mapper::complete)))
                .onErrorResume(TurnEventMapper::fail);
        ChildEnding ending = new ChildEnding();
        return threads.updateStatus(run.threadId(), ThreadStatus.RUNNING, null)
                .thenMany(mapped.concatMap(event -> write(run, ending, event)))
                .then(Mono.defer(() -> {
                    SubagentOutcome outcome = ending.outcome(run, timedOut.get());
                    StopReason stopReason = stopReasonOf(outcome);
                    log.info(
                            "子智能体结束 subagent={} childThreadId={} stopReason={}",
                            subagent.name(),
                            run.threadId(),
                            stopReason.value());
                    return threads.updateStatus(run.threadId(), ThreadStatus.IDLE, stopReason)
                            .thenReturn(outcome);
                }));
    }

    /** 写入子线程的一个事件；终态事件不写，记进 {@code ending}。 */
    private Mono<Void> write(ChildRun run, ChildEnding ending, EngineEvent event) {
        return switch (event) {
            case SessionEvent.StatusIdle _, SessionEvent.StatusRequiresAction _, SessionEvent.StatusRescheduled _ -> {
                ending.terminal = event;
                yield Mono.empty();
            }
            case SessionEvent.Error error -> {
                ending.error = error.message();
                yield events.append(
                        run.threadId(),
                        new SessionEvent.Error(error.code(), error.message(), SessionEvent.RetryStatus.TERMINAL));
            }
            default -> events.append(run.threadId(), event);
        };
    }

    private static StopReason stopReasonOf(SubagentOutcome outcome) {
        return switch (outcome) {
            case SubagentOutcome.Finished finished -> finished.stopReason();
            case SubagentOutcome.Rejected _ -> StopReason.TOOLS_DENIED;
            case SubagentOutcome.TimedOut _ -> StopReason.INTERRUPTED;
            case SubagentOutcome.Refused _ -> throw new IllegalStateException("没有拉起的子智能体没有子线程");
        };
    }

    private static Msg taskMessage(String task) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(task).build()))
                .build();
    }

    /** 子智能体执行的终态与错误，按事件顺序记录（{@code concatMap} 串行调用）。 */
    private static final class ChildEnding {

        private EngineEvent terminal;
        private String error;

        SubagentOutcome outcome(ChildRun run, boolean timedOut) {
            if (timedOut) {
                return new SubagentOutcome.TimedOut(
                        run.threadId(), run.subagent().timeout());
            }
            return switch (terminal) {
                case SessionEvent.StatusIdle idle ->
                    new SubagentOutcome.Finished(run.threadId(), idle.stopReason(), run.finalText(), error);
                case SessionEvent.StatusRequiresAction requires ->
                    new SubagentOutcome.Rejected(
                            run.threadId(),
                            requires.actions().stream()
                                    .map(PendingAction::toolName)
                                    .toList());
                // 节点下线时子智能体被 AgentScope 以 SYSTEM 来源中断；可重试的错误在子线程上不重试
                case SessionEvent.StatusRescheduled rescheduled ->
                    new SubagentOutcome.Finished(
                            run.threadId(),
                            rescheduled.reason() == SessionEvent.RescheduleReason.NODE_SHUTDOWN
                                    ? StopReason.INTERRUPTED
                                    : StopReason.ERROR,
                            run.finalText(),
                            error);
                case null, default -> {
                    log.warn("子智能体的事件流没有终态，按 error 结束 childThreadId={}", run.threadId());
                    yield new SubagentOutcome.Finished(run.threadId(), StopReason.ERROR, run.finalText(), error);
                }
            };
        }
    }

    /** 一次子智能体执行占用的资源与它的上下文。 */
    private static final class ChildRun {

        private final SubagentSlot slot;
        private final SubagentSpec subagent;
        private final AgentHandle handle;
        private final String userId;
        private final PlatformContext platform;
        private final SubagentSlot.Parent parent;
        private final PlatformStopRequest stop = new PlatformStopRequest();
        private final RuntimeContext runtimeContext;
        private final InterruptRelay relay;
        private volatile String finalText = "";

        ChildRun(
                SubagentSlot slot,
                SubagentSpec subagent,
                AgentHandle handle,
                String userId,
                PlatformContext platform,
                SubagentSlot.Parent parent) {
            this.slot = slot;
            this.subagent = subagent;
            this.handle = handle;
            this.userId = userId;
            this.platform = platform;
            this.parent = parent;
            // 从空白构建，禁止 builder(parent)：只放派生的平台上下文、会话沙箱与本次执行的停止记录（架构设计「子智能体」第 2 步）
            RuntimeContext.Builder builder = RuntimeContext.builder()
                    .sessionId(platform.threadId())
                    .userId(userId)
                    .put(PlatformContext.class, platform)
                    .put(PlatformStopRequest.class, stop);
            if (parent.sandbox() != null) {
                builder.put(
                        SandboxContext.class,
                        SandboxContext.builder()
                                .externalSandbox(parent.sandbox())
                                .build());
            }
            this.runtimeContext = builder.build();
            this.relay = new InterruptRelay(handle.agent(), userId, platform.threadId());
        }

        void observe(AgentEvent event) {
            relay.observe(event);
            if (event instanceof AgentResultEvent result && result.getResult() != null) {
                String text = result.getResult().getTextContent();
                finalText = text == null ? "" : text;
            }
        }

        SubagentSlot slot() {
            return slot;
        }

        SubagentSpec subagent() {
            return subagent;
        }

        AgentHandle handle() {
            return handle;
        }

        HarnessAgent agent() {
            return handle.agent();
        }

        String userId() {
            return userId;
        }

        String threadId() {
            return platform.threadId();
        }

        SubagentSlot.Parent parent() {
            return parent;
        }

        PlatformStopRequest stop() {
            return stop;
        }

        RuntimeContext runtimeContext() {
            return runtimeContext;
        }

        InterruptRelay relay() {
            return relay;
        }

        String finalText() {
            return finalText;
        }
    }
}
