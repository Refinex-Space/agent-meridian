/**
 * 审批上下文：待处理动作 {@code rt.pending_actions}（架构设计「待处理动作」）。
 *
 * <p>T06 提供动作的写入：轮次以 {@code session.status_requires_action} 结束时记录内核产生的动作，崩溃恢复遇到状态未知的
 * {@code non_idempotent} 调用时创建 {@code recovery_confirmation}。回应、超时与恢复轮的生成由 T08 实现。
 */
package cn.refinex.meridian.runtime.approval;
