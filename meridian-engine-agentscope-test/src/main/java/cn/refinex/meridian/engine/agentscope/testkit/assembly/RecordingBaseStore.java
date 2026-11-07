package cn.refinex.meridian.engine.agentscope.testkit.assembly;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 记录访问的内存 {@link BaseStore}：委托 harness 的 {@link InMemoryStore}，并记录每次读写的键，供测试断言
 * "某段时间内没有访问工作区"等行为。
 *
 * <p>线程安全：委托对象线程安全，访问记录用写时复制列表保存。
 */
public final class RecordingBaseStore implements BaseStore {

    private final InMemoryStore delegate = new InMemoryStore();
    private final List<String> accessedKeys = new CopyOnWriteArrayList<>();

    @Override
    public StoreItem get(List<String> namespace, String key) {
        accessedKeys.add(key);
        return delegate.get(namespace, key);
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        accessedKeys.add(key);
        delegate.put(namespace, key, value);
    }

    @Override
    public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        accessedKeys.add(key);
        return delegate.putIfVersion(namespace, key, value, expectedVersion);
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        accessedKeys.add(String.join("/", namespace) + "/*");
        return delegate.search(namespace, limit, offset);
    }

    @Override
    public void delete(List<String> namespace, String key) {
        accessedKeys.add(key);
        delegate.delete(namespace, key);
    }

    /**
     * 访问过的键，按访问顺序；列目录记为 {@code 命名空间/*}。
     *
     * @return 键
     */
    public List<String> accessedKeys() {
        return List.copyOf(accessedKeys);
    }
}
