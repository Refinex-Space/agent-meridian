package cn.refinex.meridian.runtime.sandbox.infra.system;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** 空闲回收器的扫描：跨租户找出沙箱空闲超过阈值的会话（架构设计「会话沙箱」"空闲回收"）。 */
@Repository
@CrossTenant(reason = "空闲回收器跨租户扫描持有沙箱的会话，逐个在其所属项目的上下文中加锁判定并回收")
public class IdleSandboxRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public IdleSandboxRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 找出沙箱最近一次使用早于截止时间的会话，最久未用的在前。只是候选：回收前要在会话行锁内重新判定。已删除的会话不回收（不必快照），
     * 由清理作业删除沙箱（D50 C-10，ADR-0049）。
     *
     * @param cutoff 截止时间（当前时间减空闲阈值）
     * @param limit 至多返回的条数
     * @return 候选会话
     */
    public List<IdleSession> findIdle(Instant cutoff, int limit) {
        return jdbc().sql("""
                        SELECT id, tenant_id, project_id
                          FROM rt.sessions
                         WHERE sandbox_pod_ip IS NOT NULL AND sandbox_last_used_at < :cutoff
                           AND deleted_at IS NULL
                         ORDER BY sandbox_last_used_at
                         LIMIT :limit
                        """)
                .param("cutoff", JdbcValues.timestamptz(cutoff))
                .param("limit", limit)
                .query((rs, row) ->
                        new IdleSession(rs.getString("id"), rs.getString("tenant_id"), rs.getString("project_id")))
                .list();
    }

    /**
     * 一个沙箱空闲的会话。
     *
     * @param sessionId 会话 ID
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     */
    public record IdleSession(String sessionId, String tenantId, String projectId) {}
}
