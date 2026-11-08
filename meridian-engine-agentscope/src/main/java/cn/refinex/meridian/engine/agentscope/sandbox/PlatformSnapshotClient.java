package cn.refinex.meridian.engine.agentscope.sandbox;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.spi.port.SnapshotStorePort;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.InputStream;
import java.util.Objects;

/**
 * AgentScope {@link RemoteSnapshotClient} 的平台实现：工作区快照（tar）经 {@link SnapshotStorePort} 存入对象存储，
 * 见架构设计「会话沙箱」"空闲回收"。
 *
 * <p>快照 ID 就是对象键 {@code rt/snapshots/{tenant_id}/{project_id}/{session_id}/{snapshot_id}.tar}（{@link #key}），
 * 所以序列化后的沙箱状态里的 {@code RemoteSandboxSnapshot.id} 可以直接定位对象。方法是阻塞的，在虚拟线程上调用。
 */
public final class PlatformSnapshotClient implements RemoteSnapshotClient {

    private final SnapshotStorePort store;

    /**
     * 创建快照客户端。
     *
     * @param store 快照存储端口
     */
    public PlatformSnapshotClient(SnapshotStorePort store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 生成快照的对象键。
     *
     * @param context 平台上下文，必须有租户、项目与会话
     * @param snapshotId 快照 ID（ULID）
     * @return {@code rt/snapshots/{tenant_id}/{project_id}/{session_id}/{snapshot_id}.tar}
     * @throws IllegalStateException 上下文缺少租户、项目或会话时
     */
    public static String key(PlatformContext context, String snapshotId) {
        if (context.tenantId() == null || context.projectId() == null || context.sessionId() == null) {
            throw new IllegalStateException("生成快照键需要 PlatformContext 中的租户、项目与会话");
        }
        return SnapshotStorePort.KEY_PREFIX + context.tenantId() + "/" + context.projectId() + "/" + context.sessionId()
                + "/" + snapshotId + ".tar";
    }

    /**
     * 返回绑定到本客户端、位于指定键的快照。
     *
     * @param key 快照键
     * @return AgentScope 的远程快照
     */
    public RemoteSandboxSnapshot at(String key) {
        return new RemoteSandboxSnapshot(this, key);
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        store.put(snapshotId, data.readAllBytes());
    }

    @Override
    public InputStream download(String snapshotId) {
        return store.open(snapshotId).orElseThrow(() -> new NotFoundException("快照不存在：" + snapshotId));
    }

    @Override
    public boolean exists(String snapshotId) {
        return store.exists(snapshotId);
    }
}
