package cn.refinex.meridian.engine.agentscope.testkit.port;

import cn.refinex.meridian.engine.spi.port.LeaseRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link LeaseRegistry} 的内存实现。测试用 {@link #grant} 与 {@link #revoke} 模拟 Worker 登记与移除租约。
 *
 * <p>线程安全：用并发映射保存条目。
 *
 * <p>见架构设计「端口」「租约与 fencing」。
 */
public final class InMemoryLeaseRegistry implements LeaseRegistry {

    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    private final Map<String, String> childToParent = new ConcurrentHashMap<>();
    private final Set<String> installing = ConcurrentHashMap.newKeySet();

    /**
     * 登记线程在本节点持有的租约，模拟 Worker 认领轮次。
     *
     * @param threadId 线程 ID
     * @param turnId 轮次 ID
     * @param epoch 租约 epoch
     */
    public void grant(String threadId, String turnId, long epoch) {
        leases.put(threadId, new Lease(threadId, turnId, epoch, false));
    }

    /**
     * 移除线程的租约，模拟执行结束或失去租约。
     *
     * @param threadId 线程 ID
     */
    public void revoke(String threadId) {
        leases.remove(threadId);
    }

    @Override
    public Optional<Lease> find(String threadId) {
        String owner = childToParent.getOrDefault(threadId, threadId);
        return Optional.ofNullable(leases.get(owner))
                .map(lease ->
                        new Lease(lease.leaseThreadId(), lease.turnId(), lease.epoch(), installing.contains(threadId)));
    }

    @Override
    public void attachChildThread(String childThreadId, String parentThreadId) {
        if (!leases.containsKey(parentThreadId)) {
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
        // 与 runtime 的实现相同，按被写入的线程记录：子线程安装自己的权限时同样生效
        if (installingNow) {
            installing.add(threadId);
        } else {
            installing.remove(threadId);
        }
    }
}
