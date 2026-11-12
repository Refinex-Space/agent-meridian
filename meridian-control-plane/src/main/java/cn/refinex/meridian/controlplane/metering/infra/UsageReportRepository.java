package cn.refinex.meridian.controlplane.metering.infra;

import cn.refinex.meridian.controlplane.metering.domain.UsageDimension;
import cn.refinex.meridian.controlplane.metering.domain.UsageKind;
import cn.refinex.meridian.controlplane.metering.domain.UsageReportRow;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.Date;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 用量报表的租户级仓储（ADR-0048 第 9 条）：按维度聚合 {@code cp.usage_records}，总是按计量种类与币种分组。维度只来自
 * {@link UsageDimension} 的白名单，拼进 SQL 的只有固定的列表达式。
 */
@Repository
public class UsageReportRepository extends TenantScopedRepository {

    /** 维度的列表达式；没有参与分组的维度在结果中为 NULL。 */
    private static final Map<UsageDimension, String> COLUMNS = Map.of(
            UsageDimension.DAY, "(occurred_at AT TIME ZONE 'UTC')::date",
            UsageDimension.PROJECT, "project_id",
            UsageDimension.MODEL, "model_id",
            UsageDimension.AGENT, "agent_id",
            UsageDimension.API_KEY, "api_key_id");

    /** 结果中维度的列名，按 {@link UsageReportRow} 的顺序。 */
    private static final List<UsageDimension> ORDER = List.of(
            UsageDimension.DAY,
            UsageDimension.PROJECT,
            UsageDimension.MODEL,
            UsageDimension.AGENT,
            UsageDimension.API_KEY);

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public UsageReportRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 聚合用量。
     *
     * @param projectFilter 只统计这个项目；为空表示整个租户
     * @param from 起始时间（含）
     * @param to 结束时间（不含）
     * @param dimensions 聚合维度
     * @return 每组的合计，按维度、种类、币种排序
     */
    public List<UsageReportRow> report(
            String projectFilter, Instant from, Instant to, List<UsageDimension> dimensions) {
        List<String> select = new ArrayList<>();
        List<String> group = new ArrayList<>();
        for (UsageDimension dimension : ORDER) {
            String column = COLUMNS.get(dimension);
            String alias = "d_" + dimension.value();
            if (dimensions.contains(dimension)) {
                select.add(column + " AS " + alias);
                group.add(column);
            } else {
                select.add("NULL AS " + alias);
            }
        }
        group.add("kind");
        group.add("currency");
        String sql = "SELECT " + String.join(", ", select) + ", kind, currency, count(*) AS records,"
                + " sum(quantity) AS quantity, sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens,"
                + " sum(cache_read_tokens) AS cache_read_tokens, sum(cache_write_tokens) AS cache_write_tokens,"
                + " sum(reasoning_tokens) AS reasoning_tokens, sum(cost) AS cost"
                + " FROM cp.usage_records"
                + " WHERE tenant_id = :tenant_id AND occurred_at >= :from AND occurred_at < :to"
                + " AND (CAST(:project_filter AS text) IS NULL OR project_id = :project_filter)"
                + " GROUP BY " + String.join(", ", group)
                + " ORDER BY "
                + String.join(", ", group.stream().map(c -> c + " NULLS FIRST").toList());
        return sql(sql).param("from", from)
                .param("to", to)
                .param("project_filter", projectFilter)
                .query((rs, row) -> {
                    Date day = rs.getDate("d_day");
                    return new UsageReportRow(
                            day == null ? null : day.toLocalDate(),
                            rs.getString("d_project"),
                            rs.getString("d_model"),
                            rs.getString("d_agent"),
                            rs.getString("d_api_key"),
                            UsageKind.of(rs.getString("kind")),
                            rs.getString("currency"),
                            rs.getLong("records"),
                            rs.getBigDecimal("quantity"),
                            rs.getLong("input_tokens"),
                            rs.getLong("output_tokens"),
                            rs.getLong("cache_read_tokens"),
                            rs.getLong("cache_write_tokens"),
                            rs.getLong("reasoning_tokens"),
                            rs.getBigDecimal("cost"));
                })
                .list();
    }
}
