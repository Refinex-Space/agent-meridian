package cn.refinex.meridian.controlplane.budget.infra;

import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.budget.domain.BudgetAlert;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code cp.budget_alerts} 的租户级只读仓储：控制台读取告警，按发生时间从新到旧。告警由用量消费者写入（跨租户仓储）。 */
@Repository
public class BudgetAlertRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public BudgetAlertRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 列出告警，游标为（发生时间、ID）。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 条
     */
    public List<BudgetAlert> list(PageRequest page) {
        Cursor after = page.after();
        return sql("""
                        SELECT * FROM cp.budget_alerts
                         WHERE tenant_id = :tenant_id
                           AND (CAST(:after_time AS timestamptz) IS NULL OR (occurred_at, id) < (:after_time, :after_id))
                         ORDER BY occurred_at DESC, id DESC LIMIT :limit
                        """)
                .param("after_time", after == null ? null : Instant.parse(after.key(0)))
                .param("after_id", after == null ? null : after.key(1))
                .param("limit", page.fetchSize())
                .query(BudgetAlertRepository::map)
                .list();
    }

    /**
     * 列表的游标。
     *
     * @param alert 本页最后一条
     * @return 游标
     */
    public static Cursor cursorOf(BudgetAlert alert) {
        return Cursor.of(alert.occurredAt().toString(), alert.id());
    }

    private static BudgetAlert map(ResultSet rs, int row) throws SQLException {
        return new BudgetAlert(
                rs.getString("id"),
                rs.getString("tenant_id"),
                BudgetLevel.of(rs.getString("level")),
                rs.getString("scope_id"),
                rs.getString("period"),
                rs.getString("kind"),
                rs.getLong("threshold_tokens"),
                rs.getLong("used_tokens"),
                JdbcValues.instant(rs, "occurred_at"));
    }
}
