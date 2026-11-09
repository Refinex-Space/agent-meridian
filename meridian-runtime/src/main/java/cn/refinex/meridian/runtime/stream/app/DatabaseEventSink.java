package cn.refinex.meridian.runtime.stream.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.port.EventSinkPort;
import cn.refinex.meridian.runtime.approval.app.ApprovalAudit;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * {@link EventSinkPort} 的实现：内核直接写入的子线程事件与自定义事件，以轮次租约为条件写入，提交后由写入入口发布到 Redis Stream
 * （架构设计「端口」「子智能体」）。只在流上出现的事件不持久化，直接经 {@link EventStreamPublisher} 发布。
 *
 * <p>持久化的事件经 {@link ApprovalAudit} 写入：与 Worker 写主线程事件相同，子线程的工具结果在同一事务中写
 * {@code permission.decided}（架构设计「智能体安全」：每次权限判定都写审计）。
 *
 * <p>会话、轮次与租约 epoch 取自 Reactor Context 中的 {@code PlatformContext}，子线程使用父轮次的租约；
 * 这正是"经端口的写入使用本次执行自己的 epoch"（架构设计「一轮执行的生命周期」中"同节点重新认领"）。
 * 租约条件不满足时以 {@link LeaseLostException} 结束，不发布。数据库写入在虚拟线程上执行（{@code Blocking}）。
 */
@Component
public class DatabaseEventSink implements EventSinkPort {

    private final ApprovalAudit writer;
    private final EventStreamPublisher publisher;

    /**
     * 创建事件写入端口。
     *
     * @param writer 带权限判定审计的事件写入
     * @param publisher 实时扇出
     */
    public DatabaseEventSink(ApprovalAudit writer, EventStreamPublisher publisher) {
        this.writer = writer;
        this.publisher = publisher;
    }

    @Override
    public Mono<Void> append(String threadId, EngineEvent event) {
        return PlatformContexts.currentReactive().flatMap(context -> {
            requireExecution(context);
            if (!event.type().isPersisted()) {
                return publisher.publishTransient(context.sessionId(), threadId, context.turnId(), event);
            }
            EventBatch batch =
                    EventBatch.leased(context.sessionId(), threadId, context.turnId(), context.leaseEpoch(), event);
            return Blocking.call(() -> writer.append(batch)).flatMap(result -> switch (result) {
                case AppendResult.Appended _ -> Mono.<Void>empty();
                case AppendResult.LeaseLost lost ->
                    Mono.<Void>error(
                            new LeaseLostException("轮次 " + lost.turnId() + " 的租约不是 epoch " + lost.epoch() + "，拒绝写入事件"));
            });
        });
    }

    private static void requireExecution(PlatformContext context) {
        if (context.sessionId() == null || context.turnId() == null || context.leaseEpoch() == null) {
            throw new IllegalStateException("经 EventSinkPort 写入事件需要 PlatformContext 中的会话、轮次与租约 epoch");
        }
    }
}
