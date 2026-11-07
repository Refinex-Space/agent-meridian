package cn.refinex.meridian.runtime.stream.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.port.EventSinkPort;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * {@link EventSinkPort} 的实现：内核直接写入的子线程事件与自定义事件，经 {@link SessionEventWriter} 以轮次租约为条件写入
 * （架构设计「端口」「子智能体」）。
 *
 * <p>会话、轮次与租约 epoch 取自 Reactor Context 中的 {@code PlatformContext}，子线程使用父轮次的租约；
 * 这正是"经端口的写入使用本次执行自己的 epoch"（架构设计「一轮执行的生命周期」中"同节点重新认领"）。
 * 租约条件不满足时以 {@link LeaseLostException} 结束。数据库写入在虚拟线程上执行（{@code Blocking}）。
 */
@Component
public class DatabaseEventSink implements EventSinkPort {

    private final SessionEventWriter writer;

    /**
     * 创建事件写入端口。
     *
     * @param writer 事件写入入口
     */
    public DatabaseEventSink(SessionEventWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> append(String threadId, EngineEvent event) {
        if (!event.type().isPersisted()) {
            // TODO(T06): 只在流上出现的事件不持久化，发布到 Redis Stream ev:{session_id}
            return Mono.empty();
        }
        return PlatformContexts.currentReactive()
                .map(context -> batch(context, threadId, event))
                .flatMap(batch -> Blocking.call(() -> writer.append(batch)))
                .flatMap(result -> switch (result) {
                    // TODO(T06): 写入成功后 XADD 到 Redis Stream ev:{session_id}
                    case AppendResult.Appended _ -> Mono.<Void>empty();
                    case AppendResult.LeaseLost lost ->
                        Mono.<Void>error(new LeaseLostException(
                                "轮次 " + lost.turnId() + " 的租约不是 epoch " + lost.epoch() + "，拒绝写入事件"));
                });
    }

    private static EventBatch batch(PlatformContext context, String threadId, EngineEvent event) {
        if (context.sessionId() == null || context.turnId() == null || context.leaseEpoch() == null) {
            throw new IllegalStateException("经 EventSinkPort 写入事件需要 PlatformContext 中的会话、轮次与租约 epoch");
        }
        return EventBatch.leased(context.sessionId(), threadId, context.turnId(), context.leaseEpoch(), event);
    }
}
