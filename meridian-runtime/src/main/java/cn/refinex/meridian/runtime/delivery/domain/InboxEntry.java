package cn.refinex.meridian.runtime.delivery.domain;

import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import java.util.Objects;

/**
 * 写入收件箱的一条用户消息（架构设计「消息投递」）。消息内容在对应的 {@code user.message} 事件中，收件箱只记录投递状态。
 *
 * @param sessionId 会话 ID；必填
 * @param threadId 投递目标线程；必填
 * @param eventId 对应的 {@code user.message} 事件 ID；必填
 * @param delivery 投递方式；必填
 */
public record InboxEntry(String sessionId, String threadId, String eventId, Delivery delivery) {

    /**
     * 创建收件箱记录。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public InboxEntry {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(delivery, "delivery");
    }
}
