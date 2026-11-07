package cn.refinex.meridian.runtime.stream.domain;

import cn.refinex.meridian.engine.spi.event.EngineEvent;
import java.util.List;
import java.util.Objects;

/**
 * 在一个事务里写入的一批事件：同一会话、同一线程，按列表顺序分配连续的 seq。
 *
 * @param sessionId 会话 ID；必填
 * @param threadId 事件所属线程（子线程事件为子线程）；可为空，表示会话级事件
 * @param turnId 产生事件的轮次；可为空，表示不属于某一轮（例如 {@code user.*} 事件）
 * @param fence 租约条件；必填
 * @param events 要持久化的事件，至少一个；只在流上出现的事件不能写入
 */
public record EventBatch(String sessionId, String threadId, String turnId, WriteFence fence, List<EngineEvent> events) {

    /**
     * 创建批次。
     *
     * @throws NullPointerException 缺少会话、租约条件或事件列表时
     * @throws IllegalArgumentException 没有事件，或含有只在流上出现的事件时
     */
    public EventBatch {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(fence, "fence");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (events.isEmpty()) {
            throw new IllegalArgumentException("事件批次至少要有一个事件");
        }
        for (EngineEvent event : events) {
            if (!event.type().isPersisted()) {
                throw new IllegalArgumentException(
                        "只在流上出现的事件不写入 session_events：" + event.type().value());
            }
        }
    }

    /**
     * Worker 或内核以轮次租约为条件写入的单个事件。
     *
     * @param sessionId 会话 ID
     * @param threadId 事件所属线程
     * @param turnId 持有租约的轮次
     * @param epoch 租约 epoch
     * @param event 事件
     * @return 批次
     */
    public static EventBatch leased(String sessionId, String threadId, String turnId, long epoch, EngineEvent event) {
        return new EventBatch(sessionId, threadId, turnId, WriteFence.lease(turnId, epoch), List.of(event));
    }

    /**
     * 不附加租约条件的事件，例如调用方发送的 {@code user.*} 事件。
     *
     * @param sessionId 会话 ID
     * @param threadId 事件所属线程
     * @param events 事件
     * @return 批次
     */
    public static EventBatch unfenced(String sessionId, String threadId, List<EngineEvent> events) {
        return new EventBatch(sessionId, threadId, null, WriteFence.none(), events);
    }
}
