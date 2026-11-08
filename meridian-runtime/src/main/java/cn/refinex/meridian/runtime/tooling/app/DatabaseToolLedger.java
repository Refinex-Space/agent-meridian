package cn.refinex.meridian.runtime.tooling.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.event.BlobRef;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.dispatch.domain.NodeId;
import cn.refinex.meridian.runtime.tooling.domain.ToolInvocation;
import cn.refinex.meridian.runtime.tooling.infra.ToolInvocationRepository;
import cn.refinex.meridian.runtime.tooling.infra.ToolInvocationRepository.Finished;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * {@link ToolLedgerPort} 的数据库实现：每次工具执行在发起副作用之前记录开始、结束时记录状态，崩溃恢复据此生成恢复说明
 * （架构设计「端口」「崩溃恢复」）。
 *
 * <p>写入带租约条件，与事件、状态的写入相同：在一个事务里先以 {@code FOR SHARE} 锁住持有租约的轮次再写，条件不满足时以
 * {@link LeaseLostException} 结束，什么都不写（架构设计「租约与 fencing」中的"事件与账本"）。会话、轮次与 epoch 取自 Reactor
 * Context 中的 {@code PlatformContext}，子线程的调用记在父轮次下、使用父轮次的租约。
 *
 * <p>结束记录时，在同一事务中为有副作用的调用（{@code idempotent}、{@code non_idempotent}）写审计 {@value #TOOL_EXECUTED}
 * （outbox 主题 {@code audit}），提交后计数指标 {@code meridian.tool.calls}（导出为 {@code meridian_tool_calls_total}，标签
 * {@code tool}、{@code state}）。平台工具都经 {@code PlatformTool} 记账，所以指标与审计对每个平台工具的调用都有且只有一条
 * （T10a）。
 *
 * <p>线程安全：无状态。数据库写入经 {@link Blocking} 在虚拟线程上执行。
 */
@Service
public class DatabaseToolLedger implements ToolLedgerPort {

    /** 工具执行的审计动作名（主题 {@code audit}）。 */
    public static final String TOOL_EXECUTED = "tool.executed";

    /** 沙箱命令的审计动作名（主题 {@code audit}，架构设计「智能体安全」）。 */
    public static final String SANDBOX_COMMAND = "sandbox.command";

    /** 审计记录中命令原文的上限，单位字符；超出时截断并标记，完整入参仍在 {@code rt.tool_invocations.input}。 */
    static final int AUDIT_COMMAND_MAX_CHARS = 4096;

    /** 审计的 outbox 主题（架构设计「跨服务数据流」）。 */
    static final String AUDIT_TOPIC = "audit";

    /** 工具调用计数的指标名，导出为 {@code meridian_tool_calls_total}（架构设计「可观测性」）。 */
    static final String TOOL_CALLS = "meridian.tool.calls";

    private final ToolInvocationRepository repository;
    private final TransactionTemplate transactions;
    private final OutboxWriter outbox;
    private final MeterRegistry meters;
    private final NodeId nodeId;
    private final Clock clock;

    /**
     * 创建工具账本。
     *
     * @param repository 账本仓储
     * @param transactions 事务模板（传播行为 REQUIRED）
     * @param outbox outbox 写入，审计用
     * @param meters 指标注册表
     * @param nodeId 本节点 ID，租约条件中的 {@code lease_owner}
     * @param clock 时钟
     */
    public DatabaseToolLedger(
            ToolInvocationRepository repository,
            TransactionTemplate transactions,
            OutboxWriter outbox,
            MeterRegistry meters,
            NodeId nodeId,
            Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.outbox = outbox;
        this.meters = meters;
        this.nodeId = nodeId;
        this.clock = clock;
    }

    @Override
    public Mono<String> recordStart(InvocationStart start) {
        Objects.requireNonNull(start, "start");
        return PlatformContexts.currentReactive().flatMap(context -> {
            Execution execution = Execution.of(context);
            return Blocking.call(() -> transactions.execute(status -> {
                requireLease(execution);
                String id = Ids.newId(IdPrefix.TOOL_INVOCATION);
                repository.insertRunning(
                        id, execution.sessionId(), execution.turnId(), execution.epoch(), start, clock.instant());
                return id;
            }));
        });
    }

    @Override
    public Mono<Void> recordFinish(String invocationId, Status status, BlobRef resultRef) {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(status, "status");
        if (status == Status.RUNNING) {
            throw new IllegalArgumentException("结束状态不能是 running：" + invocationId);
        }
        return PlatformContexts.currentReactive().flatMap(context -> {
            Execution execution = Execution.of(context);
            return Blocking.run(() -> {
                Finished finished = transactions.execute(tx -> {
                    requireLease(execution);
                    Finished row = repository
                            .finish(invocationId, execution.turnId(), status, resultRef, clock.instant())
                            .orElseThrow(() -> new NotFoundException("工具账本中没有执行中的记录：" + invocationId));
                    if (row.sideEffect() != SideEffect.NONE) {
                        outbox.append(AUDIT_TOPIC, audit(context, execution, row, status));
                    }
                    return row;
                });
                Counter.builder(TOOL_CALLS)
                        .description("平台工具的调用次数，按工具与结束状态")
                        .tag("tool", finished.toolName())
                        .tag("state", status.name().toLowerCase(Locale.ROOT))
                        .register(meters)
                        .increment();
            });
        });
    }

    /**
     * 写一条沙箱命令的审计（主题 {@code audit}）。命令已经执行，所以不校验租约，在独立的事务中写入。
     */
    @Override
    public Mono<Void> recordSandboxCommand(SandboxCommand command) {
        Objects.requireNonNull(command, "command");
        return PlatformContexts.currentReactive()
                .flatMap(context -> Blocking.run(() -> transactions.executeWithoutResult(
                        status -> outbox.append(AUDIT_TOPIC, sandboxCommand(context, command)))));
    }

    /**
     * 读取一轮的全部账本记录，恢复认领时生成恢复说明用。要求调用线程上已绑定该轮所在项目的 {@code PlatformContext}。
     *
     * @param turnId 轮次 ID
     * @return 记录，按开始时间排列
     */
    public List<ToolInvocation> listByTurn(String turnId) {
        return repository.listByTurn(turnId);
    }

    private ToolExecuted audit(PlatformContext context, Execution execution, Finished row, Status status) {
        return new ToolExecuted(
                TOOL_EXECUTED,
                clock.instant(),
                context.tenantId(),
                context.projectId(),
                row.sessionId(),
                row.threadId(),
                execution.turnId(),
                row.toolUseId(),
                row.toolName(),
                row.sideEffect().value(),
                status.name().toLowerCase(Locale.ROOT),
                row.idempotencyKey());
    }

    /**
     * 一次有副作用的工具执行的审计记录（主题 {@code audit}）。
     *
     * @param action 固定为 {@value #TOOL_EXECUTED}
     * @param occurredAt 结束时间
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param turnId 轮次 ID
     * @param toolUseId 工具调用 ID
     * @param toolName 工具名
     * @param sideEffect 副作用等级：{@code idempotent} 或 {@code non_idempotent}
     * @param status 结束状态：{@code succeeded}、{@code failed}、{@code interrupted}
     * @param idempotencyKey 幂等键
     */
    record ToolExecuted(
            String action,
            Instant occurredAt,
            String tenantId,
            String projectId,
            String sessionId,
            String threadId,
            String turnId,
            String toolUseId,
            String toolName,
            String sideEffect,
            String status,
            String idempotencyKey) {}

    private SandboxCommandExecuted sandboxCommand(PlatformContext context, SandboxCommand command) {
        boolean truncated = command.command().length() > AUDIT_COMMAND_MAX_CHARS;
        return new SandboxCommandExecuted(
                SANDBOX_COMMAND,
                clock.instant(),
                context.tenantId(),
                context.projectId(),
                context.sessionId(),
                command.threadId(),
                context.turnId(),
                command.toolUseId(),
                truncated ? command.command().substring(0, AUDIT_COMMAND_MAX_CHARS) : command.command(),
                truncated,
                command.exitCode(),
                command.timedOut(),
                command.duration().toMillis());
    }

    /**
     * 一条沙箱命令的审计记录（主题 {@code audit}）。
     *
     * @param action 固定为 {@value #SANDBOX_COMMAND}
     * @param occurredAt 记录时间
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param turnId 轮次 ID
     * @param toolUseId 工具调用 ID
     * @param command 命令原文，至多 {@value #AUDIT_COMMAND_MAX_CHARS} 字符
     * @param commandTruncated 命令是否被截断
     * @param exitCode 退出码
     * @param timedOut 是否超时
     * @param durationMs 耗时，单位毫秒
     */
    record SandboxCommandExecuted(
            String action,
            Instant occurredAt,
            String tenantId,
            String projectId,
            String sessionId,
            String threadId,
            String turnId,
            String toolUseId,
            String command,
            boolean commandTruncated,
            int exitCode,
            boolean timedOut,
            long durationMs) {}

    private void requireLease(Execution execution) {
        if (!repository.lockLease(execution.turnId(), nodeId.value(), execution.epoch())) {
            throw new LeaseLostException(
                    "轮次 " + execution.turnId() + " 在本节点的租约不是 epoch " + execution.epoch() + "，拒绝写入工具账本");
        }
    }

    /** 写入账本所需的执行信息，取自 {@code PlatformContext}。 */
    private record Execution(String sessionId, String turnId, long epoch) {

        static Execution of(PlatformContext context) {
            if (context.sessionId() == null || context.turnId() == null || context.leaseEpoch() == null) {
                throw new IllegalStateException("写入工具账本需要 PlatformContext 中的会话、轮次与租约 epoch");
            }
            return new Execution(context.sessionId(), context.turnId(), context.leaseEpoch());
        }
    }
}
