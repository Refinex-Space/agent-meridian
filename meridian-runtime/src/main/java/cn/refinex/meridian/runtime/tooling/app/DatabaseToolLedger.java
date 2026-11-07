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
import cn.refinex.meridian.runtime.dispatch.domain.NodeId;
import cn.refinex.meridian.runtime.tooling.domain.ToolInvocation;
import cn.refinex.meridian.runtime.tooling.infra.ToolInvocationRepository;
import java.time.Clock;
import java.util.List;
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
 * <p>线程安全：无状态。数据库写入经 {@link Blocking} 在虚拟线程上执行。
 */
@Service
public class DatabaseToolLedger implements ToolLedgerPort {

    private final ToolInvocationRepository repository;
    private final TransactionTemplate transactions;
    private final NodeId nodeId;
    private final Clock clock;

    /**
     * 创建工具账本。
     *
     * @param repository 账本仓储
     * @param transactions 事务模板（传播行为 REQUIRED）
     * @param nodeId 本节点 ID，租约条件中的 {@code lease_owner}
     * @param clock 时钟
     */
    public DatabaseToolLedger(
            ToolInvocationRepository repository, TransactionTemplate transactions, NodeId nodeId, Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
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
            return Blocking.run(() -> transactions.executeWithoutResult(tx -> {
                requireLease(execution);
                if (!repository.finish(invocationId, execution.turnId(), status, resultRef, clock.instant())) {
                    throw new NotFoundException("工具账本中没有执行中的记录：" + invocationId);
                }
            }));
        });
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
