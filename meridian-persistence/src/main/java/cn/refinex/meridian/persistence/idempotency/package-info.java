/**
 * {@code Idempotency-Key} 的通用实现：{@link cn.refinex.meridian.persistence.idempotency.Idempotency}（占用、执行、保存、重放）、
 * {@link cn.refinex.meridian.persistence.idempotency.IdempotencyStore}（租户级仓储）与
 * {@link cn.refinex.meridian.persistence.idempotency.IdempotencyPurger}（过期清理）。
 *
 * <p>幂等表由每个服务建在自己的 schema 中，建表片段在类路径
 * {@code cn/refinex/meridian/persistence/idempotency/idempotency.sql}。读取请求头与写回响应的 HTTP 集成由各服务完成。
 */
package cn.refinex.meridian.persistence.idempotency;
