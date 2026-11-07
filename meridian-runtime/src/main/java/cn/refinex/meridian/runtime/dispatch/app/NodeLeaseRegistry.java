package cn.refinex.meridian.runtime.dispatch.app;

import cn.refinex.meridian.engine.spi.port.LeaseRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * {@link LeaseRegistry} 的实现：本节点持有的线程租约（线程 ID 到 {@link LeaseHandle}），子线程登记到父轮次的租约下，
 * 以及"权限安装写入"标记（架构设计「端口」「租约与 fencing」）。
 *
 * <ul>
 *   <li>条目由 {@link TurnClaimer} 在认领事务中预留、提交后激活，由执行方在执行结束时经 {@link LeaseHandle#release()} 移除。
 *       条目存在期间本节点不会再认领该线程（{@link #heldThreadIds()} 交给认领 SQL 排除），预留本身也用
 *       {@code putIfAbsent} 保证同一线程只有一个条目（架构设计「同节点重新认领」）。
 *   <li>{@link #find} 只返回已激活、未释放、续租未发现丢失的租约；子线程返回父线程的租约。
 *   <li>权限安装标记按被写入的线程记录，子线程安装自己的权限时同样生效。
 * </ul>
 *
 * <p>线程安全：全部状态在并发映射中，方法都是内存操作，不阻塞。
 */
@Component
public final class NodeLeaseRegistry implements LeaseRegistry {

    private final Map<String, LeaseHandle> handles = new ConcurrentHashMap<>();
    private final Map<String, String> childToParent = new ConcurrentHashMap<>();
    private final Set<String> installing = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<Lease> find(String threadId) {
        String owner = childToParent.getOrDefault(threadId, threadId);
        LeaseHandle handle = handles.get(owner);
        if (handle == null || !handle.isActive() || handle.isLost()) {
            return Optional.empty();
        }
        return Optional.of(
                new Lease(owner, handle.turn().turnId(), handle.turn().epoch(), installing.contains(threadId)));
    }

    @Override
    public void attachChildThread(String childThreadId, String parentThreadId) {
        LeaseHandle parent = handles.get(parentThreadId);
        if (parent == null || !parent.isActive()) {
            throw new IllegalStateException("父线程不在本节点执行：" + parentThreadId);
        }
        childToParent.put(childThreadId, parentThreadId);
    }

    @Override
    public void detachChildThread(String childThreadId) {
        childToParent.remove(childThreadId);
        installing.remove(childThreadId);
    }

    @Override
    public void markPermissionInstall(String threadId, boolean installingNow) {
        if (installingNow) {
            installing.add(threadId);
        } else {
            installing.remove(threadId);
        }
    }

    /**
     * 本节点仍持有条目（预留或执行中、尚未释放）的线程，认领时排除它们。
     *
     * @return 线程 ID 的快照
     */
    public Set<String> heldThreadIds() {
        return Set.copyOf(handles.keySet());
    }

    /**
     * 已激活、尚未释放的租约，续租任务逐个续租。
     *
     * @return 租约的快照
     */
    public List<LeaseHandle> activeHandles() {
        return handles.values().stream().filter(LeaseHandle::isActive).toList();
    }

    /**
     * 查找线程在本节点的租约条目，T06 的 Worker 与下线流程使用。
     *
     * @param threadId 主线程 ID
     * @return 条目；本节点没有该线程的条目时为空
     */
    public Optional<LeaseHandle> handle(String threadId) {
        return Optional.ofNullable(handles.get(threadId));
    }

    /** 认领事务中预留线程的条目；线程已有条目时返回空。 */
    Optional<LeaseHandle> reserve(String threadId) {
        LeaseHandle handle = new LeaseHandle(this, threadId);
        return handles.putIfAbsent(threadId, handle) == null ? Optional.of(handle) : Optional.empty();
    }

    /** 认领事务回滚或提交失败时取消预留。 */
    void cancel(LeaseHandle handle) {
        handle.markReleased();
        handles.remove(handle.threadId(), handle);
    }

    /** 执行结束时释放条目，连同登记在它下面的子线程与权限安装标记。 */
    void release(LeaseHandle handle) {
        if (handle.isReleased()) {
            return;
        }
        handle.markReleased();
        childToParent.entrySet().removeIf(entry -> {
            boolean child = entry.getValue().equals(handle.threadId());
            if (child) {
                installing.remove(entry.getKey());
            }
            return child;
        });
        installing.remove(handle.threadId());
        handles.remove(handle.threadId(), handle);
    }
}
