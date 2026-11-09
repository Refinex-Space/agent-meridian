/**
 * 调度的外部依赖：{@code infra.system} 中的跨租户仓储（认领、续租、回收、租户并发计数），以及经 control-plane 读取租户并发配额的
 * {@link cn.refinex.meridian.runtime.dispatch.infra.ControlPlaneTenantQuotas}（ADR-0042）。
 */
package cn.refinex.meridian.runtime.dispatch.infra;
