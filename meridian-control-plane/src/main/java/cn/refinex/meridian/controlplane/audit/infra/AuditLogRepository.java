package cn.refinex.meridian.controlplane.audit.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.audit.domain.AuditEntry;
import cn.refinex.meridian.controlplane.audit.domain.AuditFilter;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code cp.audit_logs} 的租户级只读仓储：按时间、操作者、资源、动作过滤，从新到旧，游标为（发生时间、ID）。 */
@Repository
public class AuditLogRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public AuditLogRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 查询审计日志。
     *
     * @param filter 条件
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 条
     */
    public List<AuditEntry> search(AuditFilter filter, PageRequest page) {
        Cursor after = page.after();
        return sql("""
                        SELECT * FROM cp.audit_logs
                         WHERE tenant_id = :tenant_id AND occurred_at >= :from AND occurred_at < :to
                           AND (CAST(:action AS text) IS NULL OR action = :action)
                           AND (CAST(:actor AS text) IS NULL OR actor_subject = :actor)
                           AND (CAST(:resource_type AS text) IS NULL OR resource_type = :resource_type)
                           AND (CAST(:resource_id AS text) IS NULL OR resource_id = :resource_id)
                           AND (CAST(:project_filter AS text) IS NULL OR project_id = :project_filter)
                           AND (CAST(:session AS text) IS NULL OR session_id = :session)
                           AND (CAST(:after_time AS timestamptz) IS NULL OR (occurred_at, id) < (:after_time, :after_id))
                         ORDER BY occurred_at DESC, id DESC LIMIT :limit
                        """)
                .param("from", filter.from())
                .param("to", filter.to())
                .param("action", filter.action())
                .param("actor", filter.actor())
                .param("resource_type", filter.resourceType())
                .param("resource_id", filter.resourceId())
                .param("project_filter", filter.projectId())
                .param("session", filter.sessionId())
                .param("after_time", after == null ? null : Instant.parse(after.key(0)))
                .param("after_id", after == null ? null : after.key(1))
                .param("limit", page.fetchSize())
                .query(AuditLogRepository::map)
                .list();
    }

    /**
     * 列表的游标。
     *
     * @param entry 本页最后一条
     * @return 游标
     */
    public static Cursor cursorOf(AuditEntry entry) {
        return Cursor.of(entry.occurredAt().toString(), entry.id());
    }

    @SuppressWarnings("unchecked")
    private static AuditEntry map(ResultSet rs, int row) throws SQLException {
        return new AuditEntry(
                rs.getString("id"),
                JdbcValues.instant(rs, "occurred_at"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("action"),
                rs.getString("actor_type"),
                rs.getString("actor_subject"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("session_id"),
                JsonMappers.lenient().readValue(rs.getString("details"), Map.class));
    }
}
