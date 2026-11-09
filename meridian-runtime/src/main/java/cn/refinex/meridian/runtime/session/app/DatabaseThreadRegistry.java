package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.port.EventSinkPort;
import cn.refinex.meridian.engine.spi.port.ThreadRegistryPort;
import cn.refinex.meridian.runtime.session.domain.NewThread;
import cn.refinex.meridian.runtime.session.infra.ThreadRepository;
import cn.refinex.meridian.runtime.stream.app.SessionEventWriter;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * {@link ThreadRegistryPort} 的实现：子线程的行（{@code rt.threads}）与 {@code session.thread_*} 事件在同一事务中写入，事件的
 * {@code thread_id} 是子线程（架构设计「端口」「子智能体」，ADR-0040）。
 *
 * <ul>
 *   <li>创建：先以父轮次的租约为条件写 {@code session.thread_created}（锁住轮次行、再锁会话行分配 seq），再插入线程行，锁顺序与
 *       其他事件写入相同（先 {@code rt.turns}、再 {@code rt.sessions}）。租约不满足时什么都不写，以 {@link LeaseLostException}
 *       结束。
 *   <li>状态：写 {@code session.thread_status_running} 或 {@code session.thread_status_idle}，事件写入方在同一事务中推进
 *       {@code rt.threads.status}（{@code StatusChange}），所以经 {@link EventSinkPort}。
 * </ul>
 *
 * <p>会话、轮次与租约 epoch 取自 Reactor Context 中的 {@code PlatformContext}（子线程使用父轮次的租约），数据库写入经
 * {@link Blocking} 在虚拟线程上执行。线程安全：无状态。
 */
@Component
public class DatabaseThreadRegistry implements ThreadRegistryPort {

    private static final Logger log = LoggerFactory.getLogger(DatabaseThreadRegistry.class);

    private final ThreadRepository threads;
    private final SessionEventWriter writer;
    private final EventSinkPort events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建线程登记。
     *
     * @param threads 线程仓储
     * @param writer 事件写入入口
     * @param events 内核写入事件的端口，线程状态事件经它写入
     * @param transactions 事务模板（传播行为 REQUIRED）
     * @param clock 时钟
     */
    public DatabaseThreadRegistry(
            ThreadRepository threads,
            SessionEventWriter writer,
            EventSinkPort events,
            TransactionTemplate transactions,
            Clock clock) {
        this.threads = threads;
        this.writer = writer;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public Mono<String> createChildThread(String parentThreadId, String parentToolUseId, String agentName) {
        Objects.requireNonNull(parentThreadId, "parentThreadId");
        Objects.requireNonNull(parentToolUseId, "parentToolUseId");
        Objects.requireNonNull(agentName, "agentName");
        return PlatformContexts.currentReactive()
                .flatMap(context -> Blocking.call(() -> transactions.execute(status -> {
                    requireExecution(context);
                    String threadId = Ids.newId(IdPrefix.THREAD);
                    SessionEvent.ThreadCreated created =
                            new SessionEvent.ThreadCreated(threadId, parentThreadId, parentToolUseId, agentName);
                    AppendResult result = writer.append(EventBatch.leased(
                            context.sessionId(), threadId, context.turnId(), context.leaseEpoch(), created));
                    if (result instanceof AppendResult.LeaseLost lost) {
                        throw new LeaseLostException(
                                "轮次 " + lost.turnId() + " 的租约不是 epoch " + lost.epoch() + "，拒绝创建子线程");
                    }
                    NewThread thread =
                            new NewThread(threadId, context.sessionId(), parentThreadId, parentToolUseId, agentName);
                    if (!threads.insert(thread, clock.instant())) {
                        throw new IllegalStateException("会话不在当前项目中，不能创建子线程：" + context.sessionId());
                    }
                    log.debug(
                            "已创建子线程 sessionId={} threadId={} parentThreadId={}",
                            context.sessionId(),
                            threadId,
                            parentThreadId);
                    return threadId;
                })));
    }

    @Override
    public Mono<Void> updateStatus(String threadId, ThreadStatus status, StopReason stopReason) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(status, "status");
        status.check(stopReason);
        return events.append(
                threadId,
                status == ThreadStatus.RUNNING
                        ? new SessionEvent.ThreadStatusRunning(threadId)
                        : new SessionEvent.ThreadStatusIdle(threadId, stopReason));
    }

    private static void requireExecution(PlatformContext context) {
        if (context.sessionId() == null || context.turnId() == null || context.leaseEpoch() == null) {
            throw new IllegalStateException("创建子线程需要 PlatformContext 中的会话、轮次与租约 epoch");
        }
    }
}
