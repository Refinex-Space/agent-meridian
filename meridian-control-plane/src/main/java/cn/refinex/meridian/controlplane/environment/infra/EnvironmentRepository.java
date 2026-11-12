package cn.refinex.meridian.controlplane.environment.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.environment.domain.Environment;
import cn.refinex.meridian.controlplane.environment.domain.EnvironmentConfig;
import cn.refinex.meridian.controlplane.environment.domain.EnvironmentRevision;
import cn.refinex.meridian.controlplane.web.NumericCursors;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code cp.environments} 与 {@code cp.environment_revisions} 的项目级仓储（ADR-0047）。 */
@Repository
public class EnvironmentRepository extends TenantScopedRepository {

    /** 环境与当前修订的连接。 */
    private static final String SELECT_CURRENT = """
            SELECT e.*, r.tier, r.size, r.egress_allowlist
              FROM cp.environments e
              JOIN cp.environment_revisions r ON r.environment_id = e.id AND r.revision = e.revision
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public EnvironmentRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 插入环境（修订号取 {@code environment.revision()}，修订另行插入）。
     *
     * @param environment 环境，租户与项目必须等于上下文中的
     */
    public void insert(Environment environment) {
        sql("""
                        INSERT INTO cp.environments (id, tenant_id, project_id, name, revision, created_by, created_at,
                                                     updated_at)
                        VALUES (:id, :tenant_id, :project_id, :name, :revision, :created_by, :created_at, :updated_at)
                        """)
                .param("id", environment.id())
                .param("name", environment.name())
                .param("revision", environment.revision())
                .param("created_by", environment.createdBy())
                .param("created_at", environment.createdAt())
                .param("updated_at", environment.updatedAt())
                .update();
    }

    /**
     * 插入一个修订。
     *
     * @param revision 修订
     */
    public void insertRevision(EnvironmentRevision revision) {
        sql("""
                        INSERT INTO cp.environment_revisions (environment_id, revision, tenant_id, project_id, tier, size,
                                                              egress_allowlist, created_by, created_at)
                        VALUES (:environment_id, :revision, :tenant_id, :project_id, :tier, :size, :allowlist,
                                :created_by, :created_at)
                        """)
                .param("environment_id", revision.environmentId())
                .param("revision", revision.revision())
                .param("tier", revision.config().tier())
                .param("size", revision.config().size())
                .param("allowlist", JdbcValues.textArray(revision.config().egressAllowlist()))
                .param("created_by", revision.createdBy())
                .param("created_at", revision.createdAt())
                .update();
    }

    /**
     * 读取未删除的环境与当前修订。
     *
     * @param id 环境 ID
     * @param forUpdate 是否锁住环境行
     * @return 环境；不存在或已删除时为空
     */
    public Optional<Environment> find(String id, boolean forUpdate) {
        return sql(SELECT_CURRENT
                        + " WHERE e.tenant_id = :tenant_id AND e.project_id = :project_id AND e.id = :id"
                        + " AND e.deleted_at IS NULL" + (forUpdate ? " FOR UPDATE OF e" : ""))
                .param("id", id)
                .query(EnvironmentRepository::map)
                .optional();
    }

    /**
     * 按 ID 升序列出未删除的环境。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<Environment> list(PageRequest page) {
        return sql(SELECT_CURRENT + """
                         WHERE e.tenant_id = :tenant_id AND e.project_id = :project_id AND e.deleted_at IS NULL
                           AND (CAST(:after AS text) IS NULL OR e.id > :after)
                         ORDER BY e.id LIMIT :limit
                        """)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(EnvironmentRepository::map)
                .list();
    }

    /**
     * 改名字与当前修订号。
     *
     * @param id 环境 ID
     * @param name 名字
     * @param revision 当前修订号
     * @param now 修改时间
     */
    public void update(String id, String name, long revision, Instant now) {
        sql("""
                        UPDATE cp.environments SET name = :name, revision = :revision, updated_at = :now
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """)
                .param("id", id)
                .param("name", name)
                .param("revision", revision)
                .param("now", now)
                .update();
    }

    /**
     * 软删除。
     *
     * @param id 环境 ID
     * @param now 删除时间
     */
    public void softDelete(String id, Instant now) {
        sql("""
                        UPDATE cp.environments SET deleted_at = :now, updated_at = :now
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id AND deleted_at IS NULL
                        """).param("id", id).param("now", now).update();
    }

    /**
     * 按修订号升序列出修订历史（含已删除环境的）。
     *
     * @param environmentId 环境 ID
     * @param page 分页参数，游标是修订号
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<EnvironmentRevision> revisions(String environmentId, PageRequest page) {
        return sql("""
                        SELECT * FROM cp.environment_revisions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND environment_id = :environment_id
                           AND (CAST(:after AS bigint) IS NULL OR revision > :after)
                         ORDER BY revision LIMIT :limit
                        """)
                .param("environment_id", environmentId)
                .param("after", NumericCursors.after(page))
                .param("limit", page.fetchSize())
                .query(EnvironmentRepository::mapRevision)
                .list();
    }

    /**
     * 读取一个修订。
     *
     * @param environmentId 环境 ID
     * @param revision 修订号
     * @return 修订；不存在时为空
     */
    public Optional<EnvironmentRevision> revision(String environmentId, long revision) {
        return sql("""
                        SELECT * FROM cp.environment_revisions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND environment_id = :environment_id
                           AND revision = :revision
                        """)
                .param("environment_id", environmentId)
                .param("revision", revision)
                .query(EnvironmentRepository::mapRevision)
                .optional();
    }

    private static Environment map(ResultSet rs, int row) throws SQLException {
        return new Environment(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getLong("revision"),
                config(rs),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"));
    }

    /**
     * 把 {@code cp.environment_revisions} 的一行映射为修订；跨租户的查找共用。
     *
     * @param rs 结果集
     * @param row 行号
     * @return 修订
     * @throws SQLException 读取失败时
     */
    public static EnvironmentRevision mapRevision(ResultSet rs, int row) throws SQLException {
        return new EnvironmentRevision(
                rs.getString("environment_id"),
                rs.getLong("revision"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                config(rs),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"));
    }

    private static EnvironmentConfig config(ResultSet rs) throws SQLException {
        return new EnvironmentConfig(
                rs.getString("tier"), rs.getString("size"), JdbcValues.readTextArray(rs, "egress_allowlist"));
    }
}
