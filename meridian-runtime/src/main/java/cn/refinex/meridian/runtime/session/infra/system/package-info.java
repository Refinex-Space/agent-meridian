/**
 * 智能体状态的跨租户存储：{@code AgentStateRepository} 端口的实现，按线程 ID 隔离，线程 ID 只能经租约获得
 * （架构设计「租户隔离」中的端口实现豁免）。每次写入在同一事务里校验 {@code rt.turns} 上的租约。
 */
package cn.refinex.meridian.runtime.session.infra.system;
