/**
 * Redis 的薄封装：Stream 的发布、按消费组消费（确认与重投）、Pub/Sub 发布订阅，
 * 分阻塞（{@link cn.refinex.meridian.persistence.redis.RedisMessaging}）与响应式
 * （{@link cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging}）两种客户端；
 * 以及 outbox 的默认发布器 {@link cn.refinex.meridian.persistence.redis.RedisStreamOutboxPublisher}。
 *
 * <p>Redis 只做实时流、限流与通知，任何需要可靠保存的数据都以 PostgreSQL 为准（架构设计「架构原则」第 7 条）。
 * 限流与预算计数不在本包，由各自的任务实现。
 */
package cn.refinex.meridian.persistence.redis;
