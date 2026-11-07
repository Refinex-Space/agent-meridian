/**
 * 投递上下文：收件箱 {@code rt.inbox_messages}，用户消息在线程空闲、运行中、等待回应时的去向（架构设计「消息投递」）。
 *
 * <p>T06 提供收件箱仓储与 {@code InboxPort} 的数据库实现：steer 消息的带租约消费、轮次结束时把待投递的消息合并成下一轮、
 * 崩溃恢复时按 {@code consumed_epoch} 重新投递被失败执行消费的 steer。投递方式在三种线程状态下的完整行为、
 * {@code SteerMiddleware} 与中断由 T08 实现。仓储继承 {@code TenantScopedRepository}（项目级）。
 */
package cn.refinex.meridian.runtime.delivery;
