package cn.refinex.meridian.controlplane.mcp.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.mcp.domain.McpServer;
import cn.refinex.meridian.controlplane.mcp.domain.McpTool;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

/** {@code cp.mcp_servers} 的项目级仓储（ADR-0047）。 */
@Repository
public class McpServerRepository extends TenantScopedRepository {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public McpServerRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 插入 MCP 服务。
     *
     * @param server MCP 服务，租户与项目必须等于上下文中的
     */
    public void insert(McpServer server) {
        sql("""
                        INSERT INTO cp.mcp_servers (id, tenant_id, project_id, name, transport, url, header_secret_ids,
                                                    side_effect, tool_side_effects, tools, probed_at, created_by,
                                                    created_at, updated_at)
                        VALUES (:id, :tenant_id, :project_id, :name, :transport, :url, :headers, :side_effect,
                                :tool_side_effects, :tools, :probed_at, :created_by, :created_at, :updated_at)
                        """)
                .param("id", server.id())
                .param("name", server.name())
                .param("transport", server.transport())
                .param("url", server.url())
                .param("headers", JdbcValues.jsonbOf(server.headerSecretIds()))
                .param("side_effect", server.sideEffect())
                .param("tool_side_effects", JdbcValues.jsonbOf(server.toolSideEffects()))
                .param("tools", JdbcValues.jsonbOf(server.tools()))
                .param("probed_at", server.probedAt())
                .param("created_by", server.createdBy())
                .param("created_at", server.createdAt())
                .param("updated_at", server.updatedAt())
                .update();
    }

    /**
     * 整体替换可改的字段与探测结果。
     *
     * @param server 修改后的 MCP 服务
     */
    public void update(McpServer server) {
        sql("""
                        UPDATE cp.mcp_servers
                           SET name = :name, transport = :transport, url = :url, header_secret_ids = :headers,
                               side_effect = :side_effect, tool_side_effects = :tool_side_effects, tools = :tools,
                               probed_at = :probed_at, updated_at = :updated_at
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """)
                .param("id", server.id())
                .param("name", server.name())
                .param("transport", server.transport())
                .param("url", server.url())
                .param("headers", JdbcValues.jsonbOf(server.headerSecretIds()))
                .param("side_effect", server.sideEffect())
                .param("tool_side_effects", JdbcValues.jsonbOf(server.toolSideEffects()))
                .param("tools", JdbcValues.jsonbOf(server.tools()))
                .param("probed_at", server.probedAt())
                .param("updated_at", server.updatedAt())
                .update();
    }

    /**
     * 读取 MCP 服务。
     *
     * @param id MCP 服务 ID
     * @param lock 行锁：{@code null} 不加锁，{@code "UPDATE"} 或 {@code "SHARE"}
     * @return MCP 服务；不存在时为空
     */
    public Optional<McpServer> find(String id, String lock) {
        return sql("SELECT * FROM cp.mcp_servers WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id"
                        + (lock == null ? "" : " FOR " + lock))
                .param("id", id)
                .query(McpServerRepository::map)
                .optional();
    }

    /**
     * 按 ID 升序列出。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<McpServer> list(PageRequest page) {
        return sql("""
                        SELECT * FROM cp.mcp_servers
                         WHERE tenant_id = :tenant_id AND project_id = :project_id
                           AND (CAST(:after AS text) IS NULL OR id > :after)
                         ORDER BY id LIMIT :limit
                        """)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(McpServerRepository::map)
                .list();
    }

    /**
     * 删除。
     *
     * @param id MCP 服务 ID
     */
    public void delete(String id) {
        sql("DELETE FROM cp.mcp_servers WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id")
                .param("id", id)
                .update();
    }

    private static McpServer map(ResultSet rs, int row) throws SQLException {
        return new McpServer(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getString("transport"),
                rs.getString("url"),
                JsonMappers.lenient().readValue(rs.getString("header_secret_ids"), STRING_MAP),
                rs.getString("side_effect"),
                JsonMappers.lenient().readValue(rs.getString("tool_side_effects"), STRING_MAP),
                List.of(JsonMappers.lenient().readValue(rs.getString("tools"), McpTool[].class)),
                JdbcValues.instant(rs, "probed_at"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"));
    }
}
