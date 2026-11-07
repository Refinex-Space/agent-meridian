package cn.refinex.meridian.persistence.outbox;

import java.time.Instant;

/**
 * 一条 outbox 消息。
 *
 * @param id 消息 ID（ULID），在所有服务中唯一，消费方据此幂等
 * @param topic 主题，即目标 Redis Stream 的键，例如 {@code usage}、{@code audit}
 * @param payload 消息载荷，JSON 文本，字段为 snake_case
 * @param createdAt 写入 outbox 的时间
 */
public record OutboxMessage(String id, String topic, String payload, Instant createdAt) {}
