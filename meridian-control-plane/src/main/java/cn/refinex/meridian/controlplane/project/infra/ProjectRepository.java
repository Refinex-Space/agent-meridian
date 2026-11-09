package cn.refinex.meridian.controlplane.project.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.project.domain.Project;
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

/**
 * {@code cp.projects} 的租户级仓储：项目表本身是租户的数据（列出租户的项目），项目 ID 作为普通参数；已删除的项目对读写都不可见。
 */
@Repository
public class ProjectRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public ProjectRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 插入项目。
     *
     * @param project 项目，租户必须等于上下文中的租户
     */
    public void insert(Project project) {
        sql("""
                        INSERT INTO cp.projects (id, tenant_id, name, description, created_at, updated_at)
                        VALUES (:id, :tenant_id, :name, :description, :created_at, :updated_at)
                        """)
                .param("id", project.id())
                .param("name", project.name())
                .param("description", project.description())
                .param("created_at", project.createdAt())
                .param("updated_at", project.updatedAt())
                .update();
    }

    /**
     * 读取项目。
     *
     * @param projectId 项目 ID
     * @return 项目；不存在、已删除或不属于当前租户时为空
     */
    public Optional<Project> find(String projectId) {
        return sql("SELECT * FROM cp.projects WHERE tenant_id = :tenant_id AND id = :id AND deleted_at IS NULL")
                .param("id", projectId)
                .query(ProjectRepository::map)
                .optional();
    }

    /**
     * 按 ID 升序列出未删除的项目。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个项目
     */
    public List<Project> list(PageRequest page) {
        return sql("""
                        SELECT * FROM cp.projects
                         WHERE tenant_id = :tenant_id AND deleted_at IS NULL
                           AND (CAST(:after AS text) IS NULL OR id > :after)
                         ORDER BY id LIMIT :limit
                        """)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(ProjectRepository::map)
                .list();
    }

    /**
     * 修改项目。
     *
     * @param project 修改后的项目
     */
    public void update(Project project) {
        sql("""
                        UPDATE cp.projects SET name = :name, description = :description, updated_at = :updated_at
                         WHERE tenant_id = :tenant_id AND id = :id AND deleted_at IS NULL
                        """)
                .param("id", project.id())
                .param("name", project.name())
                .param("description", project.description())
                .param("updated_at", project.updatedAt())
                .update();
    }

    /**
     * 标记删除一个项目。
     *
     * @param projectId 项目 ID
     * @param now 当前时间
     */
    public void markDeleted(String projectId, Instant now) {
        sql("""
                        UPDATE cp.projects SET deleted_at = :now, updated_at = :now
                         WHERE tenant_id = :tenant_id AND id = :id AND deleted_at IS NULL
                        """).param("id", projectId).param("now", now).update();
    }

    /**
     * 标记删除当前租户的全部项目（删除租户时）。
     *
     * @param now 当前时间
     * @return 被删除的项目 ID
     */
    public List<String> markAllDeleted(Instant now) {
        return sql("""
                        UPDATE cp.projects SET deleted_at = :now, updated_at = :now
                         WHERE tenant_id = :tenant_id AND deleted_at IS NULL
                        RETURNING id
                        """).param("now", now).query((rs, row) -> rs.getString("id")).list();
    }

    private static Project map(ResultSet rs, int row) throws SQLException {
        return new Project(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("name"),
                rs.getString("description"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"));
    }
}
