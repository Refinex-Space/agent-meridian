/**
 * 待处理动作的跨租户仓储（{@code @CrossTenant}）：超时扫描按截止时间找出全部租户的过期动作。只被后台作业使用，
 * {@code api} 不依赖它（架构设计「租户隔离」）。
 */
package cn.refinex.meridian.runtime.approval.infra.system;
