package cn.refinex.meridian.runtime.sandbox.app;

import cn.refinex.meridian.engine.spi.port.SnapshotStorePort;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SnapshotStorePort} 的实现：沙箱工作区快照（tar）存入对象存储，键为
 * {@code rt/snapshots/{tenant_id}/{project_id}/{session_id}/{snapshot_id}.tar}（由 {@code SandboxDriver} 生成），见架构设计
 * 「端口」「会话沙箱」"空闲回收"。删除会话时按 {@code rt/snapshots/{tenant_id}/{project_id}/{session_id}/} 前缀清除（T17a）。
 *
 * <p>方法是阻塞的：驱动在虚拟线程上调用（AgentScope 的 {@code RemoteSnapshotClient} 是同步接口）。线程安全：无状态。
 */
public final class ObjectStoreSnapshotStore implements SnapshotStorePort {

    /** 快照的媒体类型。 */
    static final String CONTENT_TYPE = "application/x-tar";

    private final ObjectStore objects;

    /**
     * 创建快照存储。
     *
     * @param objects 对象存储客户端
     */
    public ObjectStoreSnapshotStore(ObjectStore objects) {
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    @Override
    public void put(String key, byte[] archive) {
        objects.put(checked(key), archive, CONTENT_TYPE);
    }

    @Override
    public Optional<InputStream> open(String key) {
        return objects.open(checked(key));
    }

    @Override
    public boolean exists(String key) {
        return objects.exists(checked(key));
    }

    private static String checked(String key) {
        Objects.requireNonNull(key, "key");
        if (!key.startsWith(KEY_PREFIX) || key.contains("..")) {
            throw new IllegalArgumentException("快照键必须位于 " + KEY_PREFIX + " 之下：" + key);
        }
        return key;
    }
}
