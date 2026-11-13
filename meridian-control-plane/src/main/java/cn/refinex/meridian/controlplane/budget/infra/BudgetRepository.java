package cn.refinex.meridian.controlplane.budget.infra;

import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.budget.domain.Budget;
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
 * {@code cp.budgets} 的租户级仓储（ADR-0048）。列表按级别（租户、项目、API Key、会话）、范围（默认值在前）、ID 排序，游标是这三个键。
 */
@Repository
public class BudgetRepository extends TenantScopedRepository {

    /** 级别的排序值：与 {@link BudgetLevel} 的声明顺序一致。 */
    private static final String RANK =
            "CASE level WHEN 'tenant' THEN 0 WHEN 'project' THEN 1 WHEN 'api_key' THEN 2 ELSE 3 END";

    /** 范围的排序键：默认值（空）排在具体范围之前。 */
    private static final String SCOPE_KEY = "coalesce(scope_id, '')";

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public BudgetRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 插入预算。
     *
     * @param budget 预算，租户必须等于上下文中的
     * @throws org.springframework.dao.DuplicateKeyException 同一级别、同一范围已有预算时
     */
    public void insert(Budget budget) {
        sql("""
                        INSERT INTO cp.budgets (id, tenant_id, level, scope_id, project_id, soft_limit_tokens, hard_limit_tokens,
                                                created_by, created_at, updated_at)
                        VALUES (:id, :tenant_id, :level, :scope, :project, :soft, :hard, :created_by, :created_at,
                                :updated_at)
                        """)
                .param("id", budget.id())
                .param("level", budget.level().value())
                .param("scope", budget.scopeId())
                .param("project", budget.projectId())
                .param("soft", budget.softLimitTokens())
                .param("hard", budget.hardLimitTokens())
                .param("created_by", budget.createdBy())
                .param("created_at", budget.createdAt())
                .param("updated_at", budget.updatedAt())
                .update();
    }

    /**
     * 整体替换阈值。
     *
     * @param budget 换上新阈值的预算
     */
    public void updateLimits(Budget budget) {
        sql("""
                        UPDATE cp.budgets SET soft_limit_tokens = :soft, hard_limit_tokens = :hard, updated_at = :now
                         WHERE tenant_id = :tenant_id AND id = :id
                        """)
                .param("id", budget.id())
                .param("soft", budget.softLimitTokens())
                .param("hard", budget.hardLimitTokens())
                .param("now", budget.updatedAt())
                .update();
    }

    /**
     * 删除预算。
     *
     * @param id 预算 ID
     */
    public void delete(String id) {
        sql("DELETE FROM cp.budgets WHERE tenant_id = :tenant_id AND id = :id")
                .param("id", id)
                .update();
    }

    /**
     * 读取预算。
     *
     * @param id 预算 ID
     * @param forUpdate 是否加行锁
     * @return 预算；不存在时为空
     */
    public Optional<Budget> find(String id, boolean forUpdate) {
        return sql("SELECT * FROM cp.budgets WHERE tenant_id = :tenant_id AND id = :id"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", id)
                .query(BudgetRepository::map)
                .optional();
    }

    /**
     * 按级别与范围读取一条预算。
     *
     * @param level 级别
     * @param scopeId 范围 ID；为空表示该级的默认值
     * @return 预算；没有设置时为空
     */
    public Optional<Budget> findScoped(BudgetLevel level, String scopeId) {
        return sql("""
                        SELECT * FROM cp.budgets
                         WHERE tenant_id = :tenant_id AND level = :level AND scope_id IS NOT DISTINCT FROM :scope
                        """)
                .param("level", level.value())
                .param("scope", scopeId)
                .query(BudgetRepository::map)
                .optional();
    }

    /**
     * 按级别、范围、ID 列出。
     *
     * @param level 只列出这一级；为空表示全部
     * @param page 分页参数，游标为（级别排序值、范围排序键、ID）
     * @return 最多 {@code page.fetchSize()} 条
     */
    public List<Budget> list(BudgetLevel level, PageRequest page) {
        Cursor after = page.after();
        return sql("SELECT * FROM cp.budgets WHERE tenant_id = :tenant_id"
                        + " AND (CAST(:level AS text) IS NULL OR level = :level)"
                        + " AND (CAST(:after_rank AS integer) IS NULL OR (" + RANK + ", " + SCOPE_KEY
                        + ", id) > (:after_rank, :after_scope, :after_id))"
                        + " ORDER BY " + RANK + ", " + SCOPE_KEY + ", id LIMIT :limit")
                .param("level", level == null ? null : level.value())
                .param("after_rank", after == null ? null : Integer.valueOf(after.key(0)))
                .param("after_scope", after == null ? null : after.key(1))
                .param("after_id", after == null ? null : after.key(2))
                .param("limit", page.fetchSize())
                .query(BudgetRepository::map)
                .list();
    }

    /**
     * 一级预算在一个周期的账本汇总（{@code cp.budget_usage}）。
     *
     * @param level 级别
     * @param scopeId 范围 ID
     * @param period 周期
     * @return 汇总；没有用量时为 0
     */
    public long usedTokens(BudgetLevel level, String scopeId, String period) {
        return sql("""
                        SELECT used_tokens FROM cp.budget_usage
                         WHERE tenant_id = :tenant_id AND level = :level AND scope_id = :scope AND period = :period
                        """)
                .param("level", level.value())
                .param("scope", scopeId)
                .param("period", period)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /**
     * 列表的游标。
     *
     * @param budget 本页最后一条
     * @return 游标
     */
    public static Cursor cursorOf(Budget budget) {
        return Cursor.of(
                Integer.toString(budget.level().ordinal()),
                budget.scopeId() == null ? "" : budget.scopeId(),
                budget.id());
    }

    /**
     * 映射一行。
     *
     * @param rs 结果集
     * @param row 行号
     * @return 预算
     * @throws SQLException 读取失败时
     */
    public static Budget map(ResultSet rs, int row) throws SQLException {
        return new Budget(
                rs.getString("id"),
                rs.getString("tenant_id"),
                BudgetLevel.of(rs.getString("level")),
                rs.getString("scope_id"),
                rs.getString("project_id"),
                rs.getObject("soft_limit_tokens", Long.class),
                rs.getObject("hard_limit_tokens", Long.class),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"));
    }
}
