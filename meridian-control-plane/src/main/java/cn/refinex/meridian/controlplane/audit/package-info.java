/**
 * 审计上下文（架构设计「安全架构」，ADR-0048 第 6 条）：管理操作的审计写入（outbox 主题 {@code audit}）、审计消费者（Redis Stream
 * {@code audit} → {@code cp.audit_logs}，写入的唯一入口）与审计查询。
 */
package cn.refinex.meridian.controlplane.audit;
