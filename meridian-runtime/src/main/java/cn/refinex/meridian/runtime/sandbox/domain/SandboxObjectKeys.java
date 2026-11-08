package cn.refinex.meridian.runtime.sandbox.domain;

import cn.refinex.meridian.engine.spi.port.SnapshotStorePort;

/**
 * 会话沙箱在对象存储中的键（架构设计「会话沙箱」）：产物副本 {@code rt/outputs/{tenant}/{project}/{session}/{path}}，快照
 * {@code rt/snapshots/{tenant}/{project}/{session}/{snapshot_id}.tar}（ADR-0036）。删除会话时按前缀清除。
 */
public final class SandboxObjectKeys {

    /** 产物副本的公共前缀。 */
    public static final String OUTPUTS_PREFIX = "rt/outputs/";

    private SandboxObjectKeys() {}

    /**
     * 一个会话全部产物副本的前缀。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @return 以 {@code /} 结尾的前缀
     */
    public static String outputsPrefix(String tenantId, String projectId, String sessionId) {
        return OUTPUTS_PREFIX + tenantId + "/" + projectId + "/" + sessionId + "/";
    }

    /**
     * 一个产物副本的键。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param path 相对 {@code outputs/} 的路径
     * @return 对象键
     */
    public static String output(String tenantId, String projectId, String sessionId, String path) {
        return outputsPrefix(tenantId, projectId, sessionId) + path;
    }

    /**
     * 一个会话全部快照的前缀。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @return 以 {@code /} 结尾的前缀
     */
    public static String snapshotsPrefix(String tenantId, String projectId, String sessionId) {
        return SnapshotStorePort.KEY_PREFIX + tenantId + "/" + projectId + "/" + sessionId + "/";
    }
}
