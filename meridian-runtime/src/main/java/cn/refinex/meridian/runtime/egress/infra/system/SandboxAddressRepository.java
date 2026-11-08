package cn.refinex.meridian.runtime.egress.infra.system;

import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 按沙箱 Pod 的 IP 找会话（{@code rt.sessions.sandbox_pod_ip}，架构设计「沙箱出网」）。出网代理只知道请求的源 IP，不知道租户，
 * 所以跨租户查询；查到的租户与项目随后用于建立 {@code PlatformContext} 读取环境配置。
 */
@Repository
@CrossTenant(reason = "出网代理只带源 Pod IP 来鉴权，按 IP 找会话时还不知道租户")
public class SandboxAddressRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public SandboxAddressRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 找出当前记录了这个 Pod IP、未终止的会话。正常情况下至多一个；沙箱在平台之外被重建（Pod IP 变了而会话行还没更新）时，旧 IP
     * 可能被另一个会话的新 Pod 复用，此时返回两行，由调用方拒绝。
     *
     * @param podIp 沙箱 Pod 的 IP
     * @return 会话，至多两个
     */
    public List<SandboxAddress> findByPodIp(String podIp) {
        return jdbc().sql("""
                        SELECT id, tenant_id, project_id, environment_id, environment_revision
                          FROM rt.sessions
                         WHERE sandbox_pod_ip = :pod_ip AND deleted_at IS NULL AND status <> 'terminated'
                         LIMIT 2
                        """)
                .param("pod_ip", podIp)
                .query((rs, row) -> new SandboxAddress(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("project_id"),
                        rs.getString("environment_id"),
                        rs.getLong("environment_revision")))
                .list();
    }

    /**
     * 记录了某个 Pod IP 的会话。
     *
     * @param sessionId 会话 ID
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param environmentId 环境 ID
     * @param environmentRevision 会话固化的环境修订号
     */
    public record SandboxAddress(
            String sessionId, String tenantId, String projectId, String environmentId, long environmentRevision) {}
}
