/**
 * 调度用例：{@code TurnEnqueuer} 入队，{@code TurnClaimer} 认领，{@code LeaseRenewer} 续租，{@code LeaseReaper} 回收，
 * {@code TurnTransitions} 置回队列与结束，{@code DispatchWakeups} 唤醒，{@code NodeLeaseRegistry} 登记本节点持有的租约。
 * 事务边界在这里定义。
 */
package cn.refinex.meridian.runtime.dispatch.app;
