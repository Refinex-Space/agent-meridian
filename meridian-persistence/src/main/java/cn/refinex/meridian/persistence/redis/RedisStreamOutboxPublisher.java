package cn.refinex.meridian.persistence.redis;

import cn.refinex.meridian.persistence.outbox.OutboxMessage;
import cn.refinex.meridian.persistence.outbox.OutboxPublisher;
import java.util.Map;

/**
 * {@link OutboxPublisher} 的默认实现：把消息追加到以主题命名的 Redis Stream（例如 {@code usage}、{@code audit}），
 * 见架构设计「跨服务数据流」。
 *
 * <p>条目字段：{@code id}（outbox 消息 ID，消费方据此幂等）、{@code topic}、{@code payload}（JSON）、
 * {@code created_at}（RFC 3339 UTC）。
 */
public final class RedisStreamOutboxPublisher implements OutboxPublisher {

    /** 条目字段：outbox 消息 ID。 */
    public static final String FIELD_ID = "id";

    /** 条目字段：主题。 */
    public static final String FIELD_TOPIC = "topic";

    /** 条目字段：载荷 JSON。 */
    public static final String FIELD_PAYLOAD = "payload";

    /** 条目字段：写入 outbox 的时间。 */
    public static final String FIELD_CREATED_AT = "created_at";

    private final RedisMessaging messaging;

    /**
     * 创建发布器。
     *
     * @param messaging 阻塞 Redis 客户端；OutboxRelay 在后台线程上同步发布
     */
    public RedisStreamOutboxPublisher(RedisMessaging messaging) {
        this.messaging = messaging;
    }

    @Override
    public void publish(OutboxMessage message) {
        messaging.publish(
                message.topic(),
                Map.of(
                        FIELD_ID, message.id(),
                        FIELD_TOPIC, message.topic(),
                        FIELD_PAYLOAD, message.payload(),
                        FIELD_CREATED_AT, message.createdAt().toString()));
    }
}
