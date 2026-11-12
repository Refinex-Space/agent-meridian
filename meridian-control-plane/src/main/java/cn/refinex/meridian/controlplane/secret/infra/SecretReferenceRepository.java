package cn.refinex.meridian.controlplane.secret.infra;

import cn.refinex.meridian.common.error.ResourceReference;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 凭据的引用方（ADR-0045 第 9 条）：供应商账号的 {@code secret_id} 与租户设置中联网搜索后端的 {@code secret_id}。引用方分布在其他上下文
 * 的表中，这里只读，不依赖那些上下文的代码；T15b 起在同一个查询中加入 MCP 服务。
 */
@Repository
public class SecretReferenceRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public SecretReferenceRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 一批凭据的引用方。
     *
     * @param secretIds 凭据 ID
     * @return 按凭据 ID 分组的引用方（按类型、ID 排序）；没有引用方的凭据不出现
     */
    public Map<String, List<ResourceReference>> referencesOf(Collection<String> secretIds) {
        Map<String, List<ResourceReference>> result = new LinkedHashMap<>();
        if (secretIds.isEmpty()) {
            return result;
        }
        sql("""
                        SELECT secret_id, type, id, name FROM (
                            SELECT secret_id, 'provider_account' AS type, id, name
                              FROM cp.provider_accounts
                             WHERE tenant_id = :tenant_id AND secret_id = ANY(:ids)
                            UNION ALL
                            SELECT web_search ->> 'secret_id', 'tenant_settings', tenant_id, NULL
                              FROM cp.tenant_settings
                             WHERE tenant_id = :tenant_id AND web_search ->> 'secret_id' = ANY(:ids)
                            UNION ALL
                            SELECT h.value, 'mcp_server', m.id, m.name
                              FROM cp.mcp_servers m, jsonb_each_text(m.header_secret_ids) h
                             WHERE m.tenant_id = :tenant_id AND h.value = ANY(:ids)
                            UNION ALL
                            SELECT r.ref_id, 'agent_version', v.id, a.name || ' v' || v.version
                              FROM cp.agent_version_refs r
                              JOIN cp.agent_versions v ON v.id = r.agent_version_id
                              JOIN cp.agents a ON a.id = v.agent_id
                             WHERE r.tenant_id = :tenant_id AND r.ref_type = 'secret' AND r.ref_id = ANY(:ids)
                               AND a.deleted_at IS NULL
                        ) refs
                        ORDER BY secret_id, type, id
                        """)
                .param("ids", JdbcValues.textArray(secretIds))
                .query((rs, row) -> new Row(
                        rs.getString("secret_id"),
                        new ResourceReference(rs.getString("type"), rs.getString("id"), rs.getString("name"))))
                .list()
                .forEach(row -> result.computeIfAbsent(row.secretId(), k -> new ArrayList<>())
                        .add(row.reference()));
        return result;
    }

    /** 查询的一行：被引用的凭据与引用方。 */
    private record Row(String secretId, ResourceReference reference) {}
}
