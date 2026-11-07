/**
 * 轮次的执行：{@code TurnWorker} 认领轮次并交给 {@code AgentEngine} 执行一轮，事件逐个写库并扇出，终态时在一个事务里结束轮次
 * （架构设计「一轮执行的生命周期」）；恢复认领时按 {@code RecoveryPlanner} 的判定续跑（「崩溃恢复」）；
 * {@code TurnWorkerLifecycle} 随容器启停，下线时按「节点优雅下线」的顺序处理本节点的轮次。
 *
 * <p>只经 {@code meridian-engine-spi} 的 {@code AgentEngine} 使用内核，不引用 {@code meridian-engine-agentscope}（ArchUnit 规则 2）。
 * 阻塞的数据库访问经 {@code Blocking} 在虚拟线程上执行。
 */
package cn.refinex.meridian.runtime.session.app;
