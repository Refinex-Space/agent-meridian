/**
 * 平台事件模型：{@link cn.refinex.meridian.engine.spi.event.EngineEvent} 的封闭层级与事件类型清单
 * {@link cn.refinex.meridian.engine.spi.event.EventType}，与架构设计「事件」一节的事件类型一一对应。
 *
 * <p>一个事件只有类型与 {@code data}；信封（{@code id}、{@code seq}、{@code session_id}、{@code thread_id}、
 * {@code turn_id}、{@code created_at}）由 runtime 在持久化时分配。内核输出 agent、session、span 与仅在流上
 * 出现的事件；user 域的事件由 API 写入，也使用这套类型，保证 {@code session_events} 只有一种数据模型。
 *
 * <p>边界：只有平台类型，AgentScope 事件到这里的映射在 {@code meridian-engine-agentscope}（T04）。
 * 序列化（snake_case JSON）由 runtime 完成，这里不带序列化注解。
 */
package cn.refinex.meridian.engine.spi.event;
