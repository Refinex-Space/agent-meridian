package cn.refinex.meridian.engine.spi.event;

/**
 * 平台事件的根类型，封闭层级按域分为 user、agent、session、span 与仅在流上出现的事件，每个具体记录对应
 * 架构设计「事件」一节中的一种事件类型，对应关系由 {@link EventType} 登记。
 *
 * <p>{@code AgentEngine.runTurn} 输出 agent、session、span 与流上事件；每轮有且只有一个终态事件
 * （{@code session.status_idle}、{@code session.status_requires_action} 或 {@code session.status_rescheduled}），
 * 见架构设计「事件映射」。
 */
public sealed interface EngineEvent permits UserEvent, AgentOutputEvent, SessionEvent, SpanEvent, StreamEvent {

    /**
     * 本事件的类型。
     *
     * @return 事件类型，与 {@code session_events.type} 一致
     */
    default EventType type() {
        return EventType.of(getClass());
    }
}
