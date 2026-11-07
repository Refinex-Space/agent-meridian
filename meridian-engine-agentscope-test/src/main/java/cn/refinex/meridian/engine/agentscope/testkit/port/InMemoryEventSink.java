package cn.refinex.meridian.engine.agentscope.testkit.port;

import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.port.EventSinkPort;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;

/**
 * {@link EventSinkPort} 的内存实现：按到达顺序记录事件。
 *
 * <p>线程安全：用写时复制列表保存。
 *
 * <p>见架构设计「端口」「子智能体」。
 */
public final class InMemoryEventSink implements EventSinkPort {

    private final List<Appended> events = new CopyOnWriteArrayList<>();

    @Override
    public Mono<Void> append(String threadId, EngineEvent event) {
        return Mono.fromRunnable(() -> events.add(new Appended(threadId, event)));
    }

    /**
     * 已追加的事件，按到达顺序。
     *
     * @return 事件
     */
    public List<Appended> events() {
        return List.copyOf(events);
    }

    /**
     * 某条线程的事件，按到达顺序。
     *
     * @param threadId 线程 ID
     * @return 事件
     */
    public List<EngineEvent> eventsOf(String threadId) {
        return events.stream()
                .filter(e -> e.threadId().equals(threadId))
                .map(Appended::event)
                .toList();
    }

    /**
     * 一个追加记录。
     *
     * @param threadId 线程 ID
     * @param event 事件
     */
    public record Appended(String threadId, EngineEvent event) {}
}
