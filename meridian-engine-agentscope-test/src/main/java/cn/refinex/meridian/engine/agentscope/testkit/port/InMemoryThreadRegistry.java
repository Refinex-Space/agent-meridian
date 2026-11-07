package cn.refinex.meridian.engine.agentscope.testkit.port;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.port.ThreadRegistryPort;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;

/**
 * {@link ThreadRegistryPort} 的内存实现：记录创建的子线程与每次状态更新。
 *
 * <p>线程安全：用并发集合保存，线程 ID 由 {@code Ids} 生成。
 *
 * <p>见架构设计「端口」「子智能体」。
 */
public final class InMemoryThreadRegistry implements ThreadRegistryPort {

    private final Map<String, ChildThread> threads = new ConcurrentHashMap<>();
    private final List<StatusChange> statusChanges = new CopyOnWriteArrayList<>();

    @Override
    public Mono<String> createChildThread(String parentThreadId, String parentToolUseId, String agentName) {
        return Mono.fromSupplier(() -> {
            String threadId = Ids.newId(IdPrefix.THREAD);
            threads.put(threadId, new ChildThread(threadId, parentThreadId, parentToolUseId, agentName));
            return threadId;
        });
    }

    @Override
    public Mono<Void> updateStatus(String threadId, ThreadStatus status) {
        return Mono.fromRunnable(() -> statusChanges.add(new StatusChange(threadId, status)));
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
     */
    public record StatusChange(String threadId, ThreadStatus status) {}
}
