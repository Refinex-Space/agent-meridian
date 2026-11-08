package cn.refinex.meridian.engine.spi.port;

import java.io.InputStream;
import java.util.Optional;

/**
 * 沙箱工作区快照（tar 归档）的存取，由 runtime 的 {@code sandbox} 实现（对象存储），见架构设计「端口」「会话沙箱」。
 *
 * <p>键由 {@code SandboxDriver} 生成，格式为 {@code rt/snapshots/{tenant_id}/{project_id}/{session_id}/{snapshot_id}.tar}，
 * 实现只接受 {@link #KEY_PREFIX} 之下的键。方法是同步的：AgentScope 的 {@code RemoteSnapshotClient} 是同步接口，
 * 驱动在虚拟线程上调用。
 */
public interface SnapshotStorePort {

    /** 快照键的公共前缀。 */
    String KEY_PREFIX = "rt/snapshots/";

    /**
     * 写入快照，已存在时覆盖。
     *
     * @param key 快照键
     * @param archive tar 归档内容
     * @throws IllegalArgumentException 键不在 {@link #KEY_PREFIX} 之下时
     */
    void put(String key, byte[] archive);

    /**
     * 以流的方式读取快照，调用方关闭流。
     *
     * @param key 快照键
     * @return 内容流；快照不存在时为空
     * @throws IllegalArgumentException 键不在 {@link #KEY_PREFIX} 之下时
     */
    Optional<InputStream> open(String key);

    /**
     * 判断快照是否存在。
     *
     * @param key 快照键
     * @return 是否存在
     * @throws IllegalArgumentException 键不在 {@link #KEY_PREFIX} 之下时
     */
    boolean exists(String key);
}
