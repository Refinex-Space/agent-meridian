package cn.refinex.meridian.controlplane.agent.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.agent.domain.Agent;
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

/** {@code cp.agents} 的项目级仓储，读取时带当前发布的版本（ADR-0047）。 */
@Repository
public class AgentRepository extends TenantScopedRepository {

    /** 智能体与它版本号最大的已发布版本。 */
    private static final String SELECT = """
            SELECT a.*, cv.id AS cv_id, cv.version AS cv_version, cv.published_at AS cv_published_at
              FROM cp.agents a
              LEFT JOIN LATERAL (
                    SELECT v.id, v.version, v.published_at FROM cp.agent_versions v
                     WHERE v.agent_id = a.id AND v.status = 'published'
                     ORDER BY v.version DESC LIMIT 1) cv ON TRUE
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public AgentRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 插入智能体。
     *
     * @param agent 智能体，租户与项目必须等于上下文中的
     */
    public void insert(Agent agent) {
        sql("""
                        INSERT INTO cp.agents (id, tenant_id, project_id, name, description, created_by, created_at,
                                               updated_at)
                        VALUES (:id, :tenant_id, :project_id, :name, :description, :created_by, :created_at,
                                :updated_at)
                        """)
                .param("id", agent.id())
                .param("name", agent.name())
                .param("description", agent.description())
                .param("created_by", agent.createdBy())
                .param("created_at", agent.createdAt())
                .param("updated_at", agent.updatedAt())
                .update();
    }

    /**
     * 读取未删除的智能体。
     *
     * @param id 智能体 ID
     * @param forUpdate 是否锁住智能体行（发布与新建版本按智能体串行）
     * @return 智能体；不存在或已删除时为空
     */
    public Optional<Agent> find(String id, boolean forUpdate) {
        return sql(SELECT + " WHERE a.tenant_id = :tenant_id AND a.project_id = :project_id AND a.id = :id"
                        + " AND a.deleted_at IS NULL" + (forUpdate ? " FOR UPDATE OF a" : ""))
                .param("id", id)
                .query(AgentRepository::map)
                .optional();
    }

    /**
     * 按 ID 升序列出未删除的智能体。
     *
     * @param page 分页参数
     * @param publishedOnly 是否只列出发布过版本的（公开接口）
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<Agent> list(PageRequest page, boolean publishedOnly) {
        return sql(SELECT + """
                         WHERE a.tenant_id = :tenant_id AND a.project_id = :project_id AND a.deleted_at IS NULL
                           AND (CAST(:published_only AS boolean) IS FALSE OR cv.id IS NOT NULL)
                           AND (CAST(:after AS text) IS NULL OR a.id > :after)
                         ORDER BY a.id LIMIT :limit
                        """)
                .param("published_only", publishedOnly)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(AgentRepository::map)
                .list();
    }

    /**
     * 改名字与说明。
     *
     * @param id 智能体 ID
     * @param name 名字
     * @param description 说明
     * @param now 修改时间
     */
    public void update(String id, String name, String description, Instant now) {
        sql("""
                        UPDATE cp.agents SET name = :name, description = :description, updated_at = :now
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """)
                .param("id", id)
                .param("name", name)
                .param("description", description)
                .param("now", now)
                .update();
    }

    /**
     * 软删除。
     *
     * @param id 智能体 ID
     * @param now 删除时间
     */
    public void softDelete(String id, Instant now) {
        sql("""
                        UPDATE cp.agents SET deleted_at = :now, updated_at = :now
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id AND deleted_at IS NULL
                        """).param("id", id).param("now", now).update();
    }

    private static Agent map(ResultSet rs, int row) throws SQLException {
        String currentId = rs.getString("cv_id");
        return new Agent(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"),
                currentId == null
                        ? null
                        : new Agent.PublishedVersion(
                                currentId, rs.getInt("cv_version"), JdbcValues.instant(rs, "cv_published_at")));
    }
}
