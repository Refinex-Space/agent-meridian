package cn.refinex.meridian.controlplane.policy.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.policy.domain.PolicyRule;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * {@code cp.permission_rules} 的租户级仓储（ADR-0047）：租户策略的 {@code project_id} 为空，项目策略按 {@code :project_filter} 过滤
 * （租户级仓储不能绑定名为 {@code project_id} 的参数）。
 */
@Repository
public class PolicyRuleRepository extends TenantScopedRepository {

    /** 层的条件：{@code :project_filter} 为空时是租户策略，否则是该项目的项目策略。 */
    private static final String LAYER = """
             (CAST(:project_filter AS text) IS NULL AND project_id IS NULL
              OR project_id = CAST(:project_filter AS text))""";

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public PolicyRuleRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 插入规则；同一层中已有相同的规则时不插入。
     *
     * @param rule 规则，租户必须等于上下文中的
     * @return 插入时为 true，已有相同的规则时为 false
     */
    public boolean insertIfAbsent(PolicyRule rule) {
        return sql("""
                        INSERT INTO cp.permission_rules (id, tenant_id, project_id, tool_name, rule_content, behavior,
                                                         source, source_action_id, created_by, created_at)
                        VALUES (:id, :tenant_id, :project_filter, :tool_name, :rule_content, :behavior, :source,
                                :source_action_id, :created_by, :created_at)
                        ON CONFLICT DO NOTHING
                        """)
                        .param("id", rule.id())
                        .param("project_filter", rule.projectId())
                        .param("tool_name", rule.toolName())
                        .param("rule_content", rule.ruleContent())
                        .param("behavior", rule.behavior())
                        .param("source", rule.source())
                        .param("source_action_id", rule.sourceActionId())
                        .param("created_by", rule.createdBy())
                        .param("created_at", rule.createdAt())
                        .update()
                == 1;
    }

    /**
     * 同一层中相同的规则。
     *
     * @param projectId 项目 ID；为空表示租户策略
     * @param toolName 工具名
     * @param ruleContent 规则内容；可为空
     * @param behavior 结果
     * @return 规则；没有时为空
     */
    public Optional<PolicyRule> findSame(String projectId, String toolName, String ruleContent, String behavior) {
        return sql("SELECT * FROM cp.permission_rules WHERE tenant_id = :tenant_id AND" + LAYER + """
                           AND tool_name = :tool_name AND behavior = :behavior
                           AND rule_content IS NOT DISTINCT FROM CAST(:rule_content AS text)
                        """)
                .param("project_filter", projectId)
                .param("tool_name", toolName)
                .param("rule_content", ruleContent)
                .param("behavior", behavior)
                .query(PolicyRuleRepository::map)
                .optional();
    }

    /**
     * 读取一层中的一条规则。
     *
     * @param projectId 项目 ID；为空表示租户策略
     * @param id 规则 ID
     * @return 规则；不存在或不在这一层时为空
     */
    public Optional<PolicyRule> find(String projectId, String id) {
        return sql("SELECT * FROM cp.permission_rules WHERE tenant_id = :tenant_id AND id = :id AND" + LAYER)
                .param("project_filter", projectId)
                .param("id", id)
                .query(PolicyRuleRepository::map)
                .optional();
    }

    /**
     * 按 ID（即创建顺序）列出一层的规则。
     *
     * @param projectId 项目 ID；为空表示租户策略
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 条
     */
    public List<PolicyRule> list(String projectId, PageRequest page) {
        return sql("SELECT * FROM cp.permission_rules WHERE tenant_id = :tenant_id AND" + LAYER + """
                           AND (CAST(:after AS text) IS NULL OR id > :after)
                         ORDER BY id LIMIT :limit
                        """)
                .param("project_filter", projectId)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(PolicyRuleRepository::map)
                .list();
    }

    /**
     * 一层的全部规则，按创建顺序。
     *
     * @param projectId 项目 ID；为空表示租户策略
     * @return 规则
     */
    public List<PolicyRule> all(String projectId) {
        return sql("SELECT * FROM cp.permission_rules WHERE tenant_id = :tenant_id AND" + LAYER + " ORDER BY id")
                .param("project_filter", projectId)
                .query(PolicyRuleRepository::map)
                .list();
    }

    /**
     * 删除一层中的一条规则。
     *
     * @param projectId 项目 ID；为空表示租户策略
     * @param id 规则 ID
     */
    public void delete(String projectId, String id) {
        sql("DELETE FROM cp.permission_rules WHERE tenant_id = :tenant_id AND id = :id AND" + LAYER)
                .param("project_filter", projectId)
                .param("id", id)
                .update();
    }

    private static PolicyRule map(ResultSet rs, int row) throws SQLException {
        return new PolicyRule(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("tool_name"),
                rs.getString("rule_content"),
                rs.getString("behavior"),
                rs.getString("source"),
                rs.getString("source_action_id"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"));
    }
}
