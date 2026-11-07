package cn.refinex.meridian.persistence.outbox;

/**
 * outbox 消息的发布目标，默认实现发布到 Redis Stream（{@code persistence.redis.RedisStreamOutboxPublisher}）。
 *
 * <p>投递语义是至少一次：发布成功但标记失败时，同一条消息会再次发布，所以实现不需要去重，
 * 消费方按 {@link OutboxMessage#id()} 幂等。
 */
public interface OutboxPublisher {

    /**
     * 同步发布一条消息，返回即表示已被目标接受。
     *
     * @param message 待发布的消息
     * @throws RuntimeException 发布失败时；该消息保持未发布，下一轮重试
     */
    void publish(OutboxMessage message);
}
