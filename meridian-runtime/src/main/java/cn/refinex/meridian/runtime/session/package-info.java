/**
 * 会话上下文：会话与线程的数据、智能体状态的带租约存储（{@code AgentStateRepository} 端口的实现）；
 * T06 起加入轮次 Worker 与恢复。
 *
 * <p>会话、线程按会话访问，仓储继承 {@code TenantScopedRepository}（项目级）；智能体状态按线程 ID 隔离，
 * 实现继承 {@code SystemRepository}，放在 {@code infra.system}（架构设计「租户隔离」）。
 */
package cn.refinex.meridian.runtime.session;
