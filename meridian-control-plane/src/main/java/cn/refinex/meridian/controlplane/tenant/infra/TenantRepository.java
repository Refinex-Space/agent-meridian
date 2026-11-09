package cn.refinex.meridian.controlplane.tenant.infra;

import cn.refinex.meridian.controlplane.tenant.domain.Tenant;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * {@code cp.tenants} 的租户级仓储：只读写 {@code PlatformContext} 中的那个租户（{@code id = :tenant_id}）。已删除的租户对读写都不可见。
 */
@Repository
public class TenantRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public TenantRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 插入当前上下文的租户。
     *
     * @param tenant 租户，ID 必须等于上下文中的租户
     */
    public void insert(Tenant tenant) {
        sql("""
                        INSERT INTO cp.tenants (id, name, max_concurrent_turns, event_retention_days, created_at, updated_at)
                        VALUES (:tenant_id, :name, :max, :retention, :created_at, :updated_at)
                        """)
                .param("name", tenant.name())
                .param("max", tenant.maxConcurrentTurns())
                .param("retention", tenant.eventRetentionDays())
                .param("created_at", tenant.createdAt())
                .param("updated_at", tenant.updatedAt())
                .update();
    }

    /**
     * 读取当前上下文的租户。
     *
     * @param forUpdate 是否加行锁（同一租户的修改串行）
     * @return 租户；不存在或已删除时为空
     */
    public Optional<Tenant> find(boolean forUpdate) {
        return sql("SELECT * FROM cp.tenants WHERE id = :tenant_id AND deleted_at IS NULL"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .query(TenantRepository::map)
                .optional();
    }

    /**
     * 修改名称。
     *
     * @param name 新名称
     * @param now 当前时间
     */
    public void updateName(String name, Instant now) {
        sql("UPDATE cp.tenants SET name = :name, updated_at = :now WHERE id = :tenant_id AND deleted_at IS NULL")
                .param("name", name)
                .param("now", now)
                .update();
    }

    /**
     * 修改配额。
     *
     * @param maxConcurrentTurns 并发配额
     * @param eventRetentionDays 事件保留天数
     * @param now 当前时间
     */
    public void updateQuotas(int maxConcurrentTurns, int eventRetentionDays, Instant now) {
        sql("""
                        UPDATE cp.tenants SET max_concurrent_turns = :max, event_retention_days = :retention, updated_at = :now
                         WHERE id = :tenant_id AND deleted_at IS NULL
                        """)
                .param("max", maxConcurrentTurns)
                .param("retention", eventRetentionDays)
                .param("now", now)
                .update();
    }

    /**
     * 标记删除。
     *
     * @param now 当前时间
     */
    public void markDeleted(Instant now) {
        sql("UPDATE cp.tenants SET deleted_at = :now, updated_at = :now WHERE id = :tenant_id AND deleted_at IS NULL")
                .param("now", now)
                .update();
    }

    /**
     * 把一行映射为租户；目录仓储共用。
     *
     * @param rs 结果集，当前行是 {@code cp.tenants} 的整行
     * @param row 行号
     * @return 租户
     * @throws SQLException 读取失败时
     */
    public static Tenant map(ResultSet rs, int row) throws SQLException {
        return new Tenant(
                rs.getString("id"),
                rs.getString("name"),
                rs.getInt("max_concurrent_turns"),
                rs.getInt("event_retention_days"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"));
    }
}
