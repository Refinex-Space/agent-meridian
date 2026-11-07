/**
 * 轮次调度：入队、认领、续租、回收、置回队列与租户并发计数，以及本节点持有的租约（{@code LeaseRegistry} 端口的实现）。
 *
 * <p>所有调度 SQL 都跨租户运行，仓储继承 {@code SystemRepository}，放在 {@code infra.system}（架构设计「租户隔离」）。
 * 时间一律取注入的 {@code Clock}，数值来自 {@code DispatchProperties}，默认值与架构设计一致。
 * 本包不执行智能体，执行由 T06 的 {@code session.app.TurnWorker} 负责；它从 {@code TurnClaimer} 取得 {@code LeaseHandle}，
 * 执行结束（含异常与取消）时释放。
 *
 * <p>允许依赖 {@code stream.app}（置回队列时写 {@code session.status_rescheduled}）。
 */
package cn.refinex.meridian.runtime.dispatch;
