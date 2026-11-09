package cn.refinex.meridian.engine.agentscope.testkit.port;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.port.ThreadRegistryPort;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;

/**
 * {@link ThreadRegistryPort} 的内存实现：记录创建的子线程与每次状态更新，并像 runtime 的实现一样把
 * {@code session.thread_created} 与 {@code session.thread_status_*} 写进给定的事件端口（事件属于子线程，ADR-0040），
 * 测试因此能在一个事件端口里看到子线程的完整事件序列。
 *
 * <p>线程安全：用并发集合保存，线程 ID 由 {@code Ids} 生成。
 *
 * <p>见架构设计「端口」「子智能体」。
 */
public final class InMemoryThreadRegistry implements ThreadRegistryPort {

    private final InMemoryEventSink events;
    private final Map<String, ChildThread> threads = new ConcurrentHashMap<>();
    private final List<StatusChange> statusChanges = new CopyOnWriteArrayList<>();

    /**
     * 创建登记表。
     *
     * @param events 线程事件写入的事件端口
     */
    public InMemoryThreadRegistry(InMemoryEventSink events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public Mono<String> createChildThread(String parentThreadId, String parentToolUseId, String agentName) {
        return Mono.defer(() -> {
            String threadId = Ids.newId(IdPrefix.THREAD);
            threads.put(threadId, new ChildThread(threadId, parentThreadId, parentToolUseId, agentName));
            return events.append(
                            threadId,
                            new SessionEvent.ThreadCreated(threadId, parentThreadId, parentToolUseId, agentName))
                    .thenReturn(threadId);
        });
    }

    @Override
    public Mono<Void> updateStatus(String threadId, ThreadStatus status, StopReason stopReason) {
        return Mono.defer(() -> {
            status.check(stopReason);
            statusChanges.add(new StatusChange(threadId, status, stopReason));
            return events.append(
                    threadId,
                    status == ThreadStatus.RUNNING
                            ? new SessionEvent.ThreadStatusRunning(threadId)
                            : new SessionEvent.ThreadStatusIdle(threadId, stopReason));
        });
    }

    /**
     * 创建的全部子线程。
     *
     * @return 子线程
     */
    public List<ChildThread> threads() {
        return List.copyOf(threads.values());
    }

    /**
     * 状态更新，按发生顺序。
     *
     * @return 状态更新
     */
    public List<StatusChange> statusChanges() {
        return List.copyOf(statusChanges);
    }

    /**
     * 一条子线程。
     *
     * @param threadId 线程 ID
     * @param parentThreadId 父线程 ID
     * @param parentToolUseId 拉起它的工具调用 ID
     * @param agentName 子智能体名
     */
    public record ChildThread(String threadId, String parentThreadId, String parentToolUseId, String agentName) {}

    /**
     * 一次状态更新。
     *
     * @param threadId 线程 ID
     * @param status 新状态
     * @param stopReason 停止原因；运行中为空
     */
    public record StatusChange(String threadId, ThreadStatus status, StopReason stopReason) {}
}
