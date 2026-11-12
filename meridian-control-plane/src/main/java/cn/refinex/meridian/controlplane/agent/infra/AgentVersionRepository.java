package cn.refinex.meridian.controlplane.agent.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.agent.domain.AgentVersion;
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

/**
 * {@code cp.agent_versions} 的项目级仓储（ADR-0047）：草稿可以改规格与删除，已发布的版本只读——写操作的条件都带
 * {@code status = 'draft'}，调用方检查影响行数。
 */
@Repository
public class AgentVersionRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public AgentVersionRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 下一个版本号。调用方先锁住智能体行，同一智能体的版本号不会并发分配。
     *
     * @param agentId 智能体 ID
     * @return 当前最大版本号加一
     */
    public int nextVersion(String agentId) {
        return sql("""
                        SELECT COALESCE(MAX(version), 0) + 1 FROM cp.agent_versions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND agent_id = :agent_id
                        """).param("agent_id", agentId).query(Integer.class).single();
    }

    /**
     * 插入草稿。
     *
     * @param version 版本
     */
    public void insert(AgentVersion version) {
        sql("""
                        INSERT INTO cp.agent_versions (id, tenant_id, project_id, agent_id, version, status, spec,
                                                       created_by, created_at, updated_at)
                        VALUES (:id, :tenant_id, :project_id, :agent_id, :version, :status, :spec, :created_by,
                                :created_at, :updated_at)
                        """)
                .param("id", version.id())
                .param("agent_id", version.agentId())
                .param("version", version.version())
                .param("status", version.status().value())
                .param("spec", JdbcValues.jsonb(version.spec()))
                .param("created_by", version.createdBy())
                .param("created_at", version.createdAt())
                .param("updated_at", version.updatedAt())
                .update();
    }

    /**
     * 读取一个版本。
     *
     * @param agentId 智能体 ID
     * @param version 版本号
     * @param forUpdate 是否加行锁
     * @return 版本；不存在时为空
     */
    public Optional<AgentVersion> find(String agentId, int version, boolean forUpdate) {
        return sql("""
                        SELECT * FROM cp.agent_versions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND agent_id = :agent_id
                           AND version = :version
                        """ + (forUpdate ? " FOR UPDATE" : ""))
                .param("agent_id", agentId)
                .param("version", version)
                .query(AgentVersionRepository::map)
                .optional();
    }

    /**
     * 按版本号升序列出。
     *
     * @param agentId 智能体 ID
     * @param page 分页参数，游标是版本号
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<AgentVersion> list(String agentId, PageRequest page) {
        Long after = NumericCursors.after(page);
        return sql("""
                        SELECT * FROM cp.agent_versions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND agent_id = :agent_id
                           AND (CAST(:after AS integer) IS NULL OR version > :after)
                         ORDER BY version LIMIT :limit
                        """)
                .param("agent_id", agentId)
                .param("after", after == null ? null : after.intValue())
                .param("limit", page.fetchSize())
                .query(AgentVersionRepository::map)
                .list();
    }

    /**
     * 改草稿的规格。
     *
     * @param id 版本 ID
     * @param spec 新规格 JSON
     * @param now 修改时间
     * @return 改到了草稿时为 true；已发布时为 false
     */
    public boolean updateDraft(String id, String spec, Instant now) {
        return sql("""
                        UPDATE cp.agent_versions SET spec = :spec, updated_at = :now
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id AND status = 'draft'
                        """)
                        .param("id", id)
                        .param("spec", JdbcValues.jsonb(spec))
                        .param("now", now)
                        .update()
                == 1;
    }

    /**
     * 发布草稿：写入快照后的规格，从此只读。
     *
     * @param id 版本 ID
     * @param spec 写入快照的规格 JSON
     * @param actor 发布者
     * @param now 发布时间
     * @return 发布了草稿时为 true；已发布时为 false
     */
    public boolean publish(String id, String spec, String actor, Instant now) {
        return sql("""
                        UPDATE cp.agent_versions
                           SET status = 'published', spec = :spec, published_by = :actor, published_at = :now,
                               updated_at = :now
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id AND status = 'draft'
                        """)
                        .param("id", id)
                        .param("spec", JdbcValues.jsonb(spec))
                        .param("actor", actor)
                        .param("now", now)
                        .update()
                == 1;
    }

    /**
     * 删除草稿。
     *
     * @param id 版本 ID
     * @return 删除了草稿时为 true；已发布时为 false
     */
    public boolean deleteDraft(String id) {
        return sql("""
                        DELETE FROM cp.agent_versions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id AND status = 'draft'
                        """).param("id", id).update() == 1;
    }

    /**
     * 把一行映射为版本；跨租户的查找共用。
     *
     * @param rs 结果集，当前行是 {@code cp.agent_versions} 的整行
     * @param row 行号
     * @return 版本
     * @throws SQLException 读取失败时
     */
    public static AgentVersion map(ResultSet rs, int row) throws SQLException {
        return new AgentVersion(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("agent_id"),
                rs.getInt("version"),
                AgentVersion.Status.of(rs.getString("status")),
                rs.getString("spec"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"),
                rs.getString("published_by"),
                JdbcValues.instant(rs, "published_at"));
    }
}
