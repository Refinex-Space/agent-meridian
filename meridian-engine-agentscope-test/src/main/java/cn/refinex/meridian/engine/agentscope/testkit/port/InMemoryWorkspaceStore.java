package cn.refinex.meridian.engine.agentscope.testkit.port;

import cn.refinex.meridian.engine.spi.port.WorkspaceStorePort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link WorkspaceStorePort} 的内存实现：与数据库实现相同的版本语义（从 1 开始，每次写入加一；{@code putIfVersion} 的 0 表示
 * 期望不存在），{@code search} 含子命名空间、按键排序分页。
 *
 * <p>线程安全：写入在实例锁内完成。
 */
public final class InMemoryWorkspaceStore implements WorkspaceStorePort {

    private final Map<Key, Item> items = new ConcurrentHashMap<>();

    @Override
    public Optional<Item> get(List<String> namespace, String key) {
        return Optional.ofNullable(items.get(new Key(List.copyOf(namespace), key)));
    }

    @Override
    public synchronized void put(List<String> namespace, String key, Map<String, Object> value) {
        Key k = new Key(List.copyOf(namespace), key);
        Item current = items.get(k);
        items.put(k, new Item(namespace, key, value, current == null ? 1 : current.version() + 1));
    }

    @Override
    public synchronized boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        Key k = new Key(List.copyOf(namespace), key);
        Item current = items.get(k);
        long actual = current == null ? 0 : current.version();
        if (actual != expectedVersion) {
            return false;
        }
        items.put(k, new Item(namespace, key, value, actual + 1));
        return true;
    }

    @Override
    public List<Item> search(List<String> namespace, int limit, int offset) {
        List<Item> matched = new ArrayList<>();
        for (Map.Entry<Key, Item> entry : items.entrySet()) {
            List<String> ns = entry.getKey().namespace();
            if (ns.size() >= namespace.size() && ns.subList(0, namespace.size()).equals(namespace)) {
                matched.add(entry.getValue());
            }
        }
        matched.sort(Comparator.comparing(Item::key));
        return matched.stream().skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized void delete(List<String> namespace, String key) {
        items.remove(new Key(List.copyOf(namespace), key));
    }

    /**
     * 全部条目，断言用。
     *
     * @return 条目
     */
    public List<Item> all() {
        return List.copyOf(items.values());
    }

    private record Key(List<String> namespace, String key) {}
}
