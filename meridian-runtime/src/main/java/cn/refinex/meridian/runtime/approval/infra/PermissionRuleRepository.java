package cn.refinex.meridian.runtime.approval.infra;

import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 权限规则 {@code rt.permission_rules} 的项目级仓储：租户、项目、会话三层（架构设计「权限判定」）。方法在调用方的事务中执行。
 *
 * <p>租户层的规则不属于任何项目，对租户下的全部项目生效，所以按项目读取时一并返回本租户的租户层规则。
 */
@Repository
public class PermissionRuleRepository extends TenantScopedRepository {

    private static final String COLUMNS = "layer, tool_name, rule_content, behavior";

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public PermissionRuleRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 读取当前项目适用的租户层与项目层规则，按层（租户在前）与写入顺序排列。
     *
     * @return 规则，{@link PermissionRule#layer()} 为 {@link PolicyLayer#TENANT} 或 {@link PolicyLayer#PROJECT}
     */
    public List<PermissionRule> listTenantAndProject() {
        return sql("SELECT " + COLUMNS + """
                          FROM rt.permission_rules
                         WHERE tenant_id = :tenant_id
                           AND (layer = 'tenant' OR (layer = 'project' AND project_id = :project_id))
                         ORDER BY layer DESC, id
                        """)
                .query(PermissionRuleRepository::map)
                .list();
    }

    /**
     * 读取会话层规则，按写入顺序排列。
     *
     * @param sessionId 会话 ID
     * @return 规则
     */
    public List<PermissionRule> listSession(String sessionId) {
        return sql("SELECT " + COLUMNS + """
                          FROM rt.permission_rules
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND layer = 'session'
                           AND session_id = :session_id
                         ORDER BY id
                        """)
                .param("session_id", sessionId)
                .query(PermissionRuleRepository::map)
                .list();
    }

    /**
     * 写入一条项目层或会话层规则；同一作用域里已有相同的规则（工具、内容、结果都相同）时不重复写入。
     *
     * @param rule 规则，层必须是 {@link PolicyLayer#PROJECT} 或 {@link PolicyLayer#SESSION}
     * @param sessionId 会话 ID；会话层必填，项目层为空
     * @param sourceActionId 来源的待处理动作 ID；可为空
     * @param createdBy 写入它的主体；可为空
     * @param now 当前时间
     * @return 写入了新规则时为 true
     * @throws IllegalArgumentException 层不是项目或会话，或会话 ID 与层不一致时
     */
    public boolean insert(PermissionRule rule, String sessionId, String sourceActionId, String createdBy, Instant now) {
        if (rule.layer() != PolicyLayer.PROJECT && rule.layer() != PolicyLayer.SESSION) {
            throw new IllegalArgumentException("只能写入项目层或会话层规则：" + rule.layer());
        }
        if ((rule.layer() == PolicyLayer.SESSION) != (sessionId != null)) {
            throw new IllegalArgumentException("会话层规则必须带会话 ID，项目层规则不能带：" + rule.layer());
        }
        return sql("""
                                INSERT INTO rt.permission_rules (id, tenant_id, project_id, session_id, layer, tool_name,
                                                                 rule_content, behavior, source_action_id, created_by,
                                                                 created_at)
                                SELECT :id, :tenant_id, :project_id, :session_id, :layer, :tool_name, :rule_content,
                                       :behavior, :source_action_id, :created_by, :now
                                 WHERE NOT EXISTS (
                                       SELECT 1 FROM rt.permission_rules
                                        WHERE tenant_id = :tenant_id AND project_id = :project_id AND layer = :layer
                                          AND session_id IS NOT DISTINCT FROM CAST(:session_id AS text)
                                          AND tool_name = :tool_name
                                          AND rule_content IS NOT DISTINCT FROM CAST(:rule_content AS text)
                                          AND behavior = :behavior)
                                """)
                        .param("id", Ids.newUlid())
                        .param("session_id", sessionId)
                        .param("layer", rule.layer().value())
                        .param("tool_name", rule.toolName())
                        .param("rule_content", rule.ruleContent())
                        .param("behavior", rule.behavior().value())
                        .param("source_action_id", sourceActionId)
                        .param("created_by", createdBy)
                        .param("now", now)
                        .update()
                == 1;
    }

    private static PermissionRule map(ResultSet rs, int row) throws SQLException {
        return new PermissionRule(
                rs.getString("tool_name"),
                rs.getString("rule_content"),
                behaviorOf(rs.getString("behavior")),
                layerOf(rs.getString("layer")));
    }

    private static PermissionBehavior behaviorOf(String value) {
        for (PermissionBehavior behavior : PermissionBehavior.values()) {
            if (behavior.value().equals(value)) {
                return behavior;
            }
        }
        throw new IllegalArgumentException("未知的规则结果：" + value);
    }

    private static PolicyLayer layerOf(String value) {
        for (PolicyLayer layer : PolicyLayer.values()) {
            if (layer.value().equals(value)) {
                return layer;
            }
        }
        throw new IllegalArgumentException("未知的规则层：" + value);
    }
}
