package cn.refinex.meridian.engine.agentscope.testkit.port;

import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * {@link AgentStateRepository} 的内存实现。数据库中"该线程的活动轮次处于 running 且 epoch 匹配"的租约条件，
 * 用 {@link #activate} 登记的活动 epoch 模拟；{@link #deactivate} 模拟租约被回收或被其他节点认领。
 *
 * <p>线程安全：所有方法都在本对象的监视器内执行，与数据库中的单条事务对应。
 *
 * <p>见架构设计「端口」「租约与 fencing」。
 */
public final class InMemoryAgentStateRepository implements AgentStateRepository {

    private final Map<String, Long> activeEpochs = new ConcurrentHashMap<>();
    private final Map<StateKey, Row> rows = new ConcurrentHashMap<>();
    private final AtomicInteger writes = new AtomicInteger();

    /**
     * 登记线程的活动轮次与 epoch，此后带该 epoch 的写入才能成功。
     *
     * @param leaseThreadId 持有租约的线程
     * @param epoch 租约 epoch
     */
    public void activate(String leaseThreadId, long epoch) {
        activeEpochs.put(leaseThreadId, epoch);
    }

    /**
     * 撤销线程的活动轮次，此后该线程的所有写入都失败。
     *
     * @param leaseThreadId 持有租约的线程
     */
    public void deactivate(String leaseThreadId) {
        activeEpochs.remove(leaseThreadId);
    }

    @Override
    public synchronized Optional<StoredState> find(StateKey key) {
        Row row = rows.get(key);
        return row == null ? Optional.empty() : Optional.of(new StoredState(row.json, row.version));
    }

    @Override
    public synchronized long save(StateKey key, String json, StateFence fence) {
        checkFence(fence);
        Row previous = rows.get(key);
        return write(key, json, previous, fence);
    }

    @Override
    public synchronized OptionalLong saveIfVersion(StateKey key, String json, long expectedVersion, StateFence fence) {
        checkFence(fence);
        Row previous = rows.get(key);
        long current = previous == null ? 0 : previous.version;
        if (current != expectedVersion) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(write(key, json, previous, fence));
    }

    @Override
    public synchronized boolean exists(String userId, String threadId) {
        return rows.keySet().stream()
                .anyMatch(k -> k.userId().equals(userId) && k.threadId().equals(threadId));
    }

    @Override
    public synchronized void delete(StateKey key, StateFence fence) {
        checkFence(fence);
        rows.remove(key);
        writes.incrementAndGet();
    }

    @Override
    public synchronized void deleteAll(String userId, String threadId, StateFence fence) {
        checkFence(fence);
        rows.keySet().removeIf(k -> k.userId().equals(userId) && k.threadId().equals(threadId));
        writes.incrementAndGet();
    }

    @Override
    public synchronized Set<String> listThreadIds(String userId) {
        return rows.keySet().stream()
                .filter(k -> k.userId().equals(userId))
                .map(StateKey::threadId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized OptionalLong contentEpoch(String userId, String threadId) {
        return rows.entrySet().stream()
                .filter(e -> e.getKey().userId().equals(userId)
                        && e.getKey().threadId().equals(threadId))
                .mapToLong(e -> e.getValue().contentEpoch)
                .filter(epoch -> epoch > 0)
                .max();
    }

    /**
     * 成功写入（含删除）的次数，用于断言"取消订阅后不再写入"。
     *
     * @return 次数
     */
    public int writeCount() {
        return writes.get();
    }

    private void checkFence(StateFence fence) {
        Long active = activeEpochs.get(fence.leaseThreadId());
        if (active == null || active != fence.epoch()) {
            throw new LeaseLostException("线程 " + fence.leaseThreadId() + " 的租约不是 epoch " + fence.epoch());
        }
    }

    private long write(StateKey key, String json, Row previous, StateFence fence) {
        long version = previous == null ? 1 : previous.version + 1;
        long contentEpoch = fence.contentWrite() ? fence.epoch() : previous == null ? 0 : previous.contentEpoch;
        rows.put(key, new Row(json, version, contentEpoch));
        writes.incrementAndGet();
        return version;
    }

    /** 一行状态；{@code contentEpoch} 为 0 表示还没有内容写入。 */
    private record Row(String json, long version, long contentEpoch) {}
}
