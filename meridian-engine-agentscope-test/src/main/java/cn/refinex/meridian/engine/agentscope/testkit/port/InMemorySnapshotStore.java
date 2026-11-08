package cn.refinex.meridian.engine.agentscope.testkit.port;

import cn.refinex.meridian.engine.spi.port.SnapshotStorePort;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SnapshotStorePort} 的内存实现，与 runtime 的实现一样拒绝 {@link SnapshotStorePort#KEY_PREFIX} 之外的键。
 *
 * <p>线程安全：用并发映射保存。见架构设计「端口」「会话沙箱」。
 */
public final class InMemorySnapshotStore implements SnapshotStorePort {

    private final Map<String, byte[]> snapshots = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] archive) {
        snapshots.put(checked(key), archive.clone());
    }

    @Override
    public Optional<InputStream> open(String key) {
        return Optional.ofNullable(snapshots.get(checked(key))).map(ByteArrayInputStream::new);
    }

    @Override
    public boolean exists(String key) {
        return snapshots.containsKey(checked(key));
    }

    /**
     * 返回已保存的快照键。
     *
     * @return 快照键
     */
    public Set<String> keys() {
        return Set.copyOf(snapshots.keySet());
    }

    /**
     * 返回快照的大小。
     *
     * @param key 快照键
     * @return 字节数；不存在时为 -1
     */
    public long size(String key) {
        byte[] content = snapshots.get(key);
        return content == null ? -1 : content.length;
    }

    private static String checked(String key) {
        if (!key.startsWith(KEY_PREFIX)) {
            throw new IllegalArgumentException("快照键必须以 " + KEY_PREFIX + " 开头：" + key);
        }
        return key;
    }
}
