package cn.refinex.meridian.controlplane.agent.infra;

import cn.refinex.meridian.common.error.ResourceReference;
import cn.refinex.meridian.controlplane.agent.domain.SpecReference;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.util.Collection;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * {@code cp.agent_version_refs} 的租户级仓储：已发布版本引用的资源（ADR-0047）。模型、凭据是租户级资源，引用方可以在租户内任何项目，
 * 所以按租户查询；删除模型、技能、MCP 服务、凭据之前据此列出未删除智能体的已发布版本。
 */
@Repository
public class AgentVersionRefRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public AgentVersionRefRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 记下一个已发布版本的引用。
     *
     * @param agentVersionId 智能体版本 ID
     * @param references 引用
     */
    public void insert(String agentVersionId, Collection<SpecReference> references) {
        for (SpecReference reference : references) {
            sql("""
                            INSERT INTO cp.agent_version_refs (agent_version_id, tenant_id, ref_type, ref_id)
                            VALUES (:version, :tenant_id, :type, :ref) ON CONFLICT DO NOTHING
                            """)
                    .param("version", agentVersionId)
                    .param("type", reference.type().value())
                    .param("ref", reference.id())
                    .update();
        }
    }

    /**
     * 引用某个资源的、未删除智能体的已发布版本。
     *
     * @param type 资源类型
     * @param refId 资源 ID
     * @return 引用方（类型 {@code agent_version}，名称为"智能体名 v版本号"），按版本 ID 排序
     */
    public List<ResourceReference> referencing(SpecReference.Type type, String refId) {
        return sql("""
                        SELECT v.id, a.name, v.version
                          FROM cp.agent_version_refs r
                          JOIN cp.agent_versions v ON v.id = r.agent_version_id
                          JOIN cp.agents a ON a.id = v.agent_id
                         WHERE r.tenant_id = :tenant_id AND r.ref_type = :type AND r.ref_id = :ref
                           AND a.deleted_at IS NULL
                         ORDER BY v.id
                        """)
                .param("type", type.value())
                .param("ref", refId)
                .query((rs, row) -> new ResourceReference(
                        "agent_version", rs.getString("id"), rs.getString("name") + " v" + rs.getInt("version")))
                .list();
    }
}
