package cn.refinex.meridian.engine.spi.engine.input;

import java.util.List;
import java.util.Objects;

/**
 * 进入一轮的一条用户消息，来自已经持久化的 {@code user.message} 事件，见架构设计「消息投递」。
 *
 * <p>同一轮可以合并多条 queue 消息；steer 消息由 {@code SteerMiddleware} 在推理前经 {@code InboxPort} 读出，
 * 也使用本类型。
 *
 * @param eventId 对应的 {@code user.message} 事件 ID（{@code evt_…}），用于 {@code session.message_consumed}；必填
 * @param content 内容块，至少一个
 */
public record InboundMessage(String eventId, List<MessageContent> content) {

    /**
     * 创建用户消息。
     *
     * @throws NullPointerException 缺少事件 ID 时
     * @throws IllegalArgumentException 没有内容块时
     */
    public InboundMessage {
        Objects.requireNonNull(eventId, "eventId");
        content = content == null ? List.of() : List.copyOf(content);
        if (content.isEmpty()) {
            throw new IllegalArgumentException("用户消息至少要有一个内容块：" + eventId);
        }
    }
}
