package cn.refinex.meridian.engine.agentscope.fs;

import cn.refinex.meridian.engine.spi.port.WorkspaceStorePort;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 平台的 {@link BaseStore}：{@code none} 档工作区（{@code RemoteFilesystem}）的存储，委托给 runtime 实现的
 * {@link WorkspaceStorePort}（表 {@code rt.workspace_entries}，64 KB 以上的内容进对象存储），见架构设计「文件系统装配」。
 *
 * <p>必须覆盖 {@link #putIfVersion}：AgentScope 的默认实现返回 false，{@code RemoteFilesystem.write} 用它做"不存在才创建"，
 * 不覆盖时所有写入都失败。命名空间由 {@link WorkspaceNamespaces} 生成，本类只透传。
 *
 * <p>线程安全：无状态。
 */
public final class MeridianBaseStore implements BaseStore {

    private final WorkspaceStorePort port;

    /**
     * 创建存储。
     *
     * @param port 工作区存储端口
     */
    public MeridianBaseStore(WorkspaceStorePort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        return port.get(namespace, key).map(MeridianBaseStore::toItem).orElse(null);
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        port.put(namespace, key, value);
    }

    @Override
    public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        return port.putIfVersion(namespace, key, value, expectedVersion);
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        return port.search(namespace, limit, offset).stream()
                .map(MeridianBaseStore::toItem)
                .toList();
    }

    @Override
    public void delete(List<String> namespace, String key) {
        port.delete(namespace, key);
    }

    private static StoreItem toItem(WorkspaceStorePort.Item item) {
        return new StoreItem(item.key(), item.value(), item.version());
    }
}
