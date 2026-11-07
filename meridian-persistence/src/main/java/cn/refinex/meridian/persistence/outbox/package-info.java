/**
 * outbox 模式（架构设计「跨服务数据流」）：{@link cn.refinex.meridian.persistence.outbox.OutboxWriter} 在业务事务内写
 * {@code outbox} 表，{@link cn.refinex.meridian.persistence.outbox.OutboxRelay} 以 {@code SKIP LOCKED} 批量取出、
 * 经 {@link cn.refinex.meridian.persistence.outbox.OutboxPublisher} 发布、标记。
 *
 * <p>{@code outbox} 表由使用它的服务建在自己的 schema 中，建表片段在类路径
 * {@code cn/refinex/meridian/persistence/outbox/outbox.sql}，复制到该服务的第一个迁移。投递语义为至少一次，
 * 消费方按消息 ID 幂等。消费方（control-plane 的用量与审计消费者）不在本包。
 */
package cn.refinex.meridian.persistence.outbox;
