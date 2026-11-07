/**
 * 调度的跨租户仓储：{@code rt.turns}、{@code rt.turn_inputs}、{@code rt.tenant_concurrency} 上的认领、续租、回收与计数。
 * 每条带条件的更新都返回影响行数或可空结果，由 {@code dispatch.app} 转成明确的结果类型。
 */
package cn.refinex.meridian.runtime.dispatch.infra.system;
