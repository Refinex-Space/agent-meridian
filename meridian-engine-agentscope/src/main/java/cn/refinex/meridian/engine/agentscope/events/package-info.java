/**
 * 事件映射：把 AgentScope 一次调用的 {@code AgentEvent} 流翻译为平台的 {@code EngineEvent} 流，按
 * 架构设计「事件映射」表与「状态机」中的停止原因映射实现。
 *
 * <ul>
 *   <li>{@link cn.refinex.meridian.engine.agentscope.events.AgentEventDisposition}：31 个 {@code AgentEventType}
 *       各自的处理方式，穷举。
 *   <li>{@link cn.refinex.meridian.engine.agentscope.events.StopReasonMapping}：{@code GenerateReason} 与异常到终态的映射，穷举。
 *   <li>{@link cn.refinex.meridian.engine.agentscope.events.TurnEventMapper}：一轮的有状态映射器，按 {@code blockId}、
 *       工具调用 ID 拼装增量，保证每轮恰好一个终态。
 * </ul>
 *
 * <p>本包不订阅智能体、不写任何存储（大对象经 {@code BlobStorePort} 写入除外）；执行一轮在 {@code turn} 包。
 *
 * <p>允许的依赖：{@code meridian-common}、{@code meridian-engine-spi}、AgentScope 2.0.4。
 */
package cn.refinex.meridian.engine.agentscope.events;
