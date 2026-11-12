/**
 * 审计消息的共用约定（架构设计「跨服务数据流」，ADR-0048 第 6 条）：control-plane 与 runtime 写 outbox 主题 {@code audit} 时的操作者。
 */
package cn.refinex.meridian.common.audit;
