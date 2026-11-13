package cn.refinex.meridian.controlplane.metering.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.metering.domain.UsageKind;
import cn.refinex.meridian.controlplane.metering.domain.UsageRange;
import cn.refinex.meridian.controlplane.metering.domain.UsageRecord;
import cn.refinex.meridian.controlplane.metering.domain.UsageRecordFilter;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.ScopedStatement;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

/**
 * 账本明细的查询（{@code usage-records}，D68，ADR-0053）：按发生时间从新到旧，走 {@code usage_records_tenant_time_idx} 与
 * {@code usage_records_project_time_idx}，按月分区的范围裁剪由 {@code occurred_at} 条件完成。
 */
@Repository
public class UsageRecordRepository extends TenantScopedRepository {

    private static final TypeReference<Map<String, Object>> ATTRIBUTES = new TypeReference<>() {};

    private static final String SELECT = """
            SELECT id, occurred_at, kind, tenant_id, project_id, api_key_id, agent_id, agent_version_id, session_id,
                   thread_id, turn_id, request_id, model_id, provider_account_id, upstream_model, purpose, fallback,
                   usage_source, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens,
                   quantity, price_book_id, price_book_version, currency, cost, attributes::text AS attributes
              FROM cp.usage_records
             WHERE tenant_id = :tenant_id AND occurred_at >= :from AND occurred_at < :to
               AND (CAST(:project_filter AS text) IS NULL OR project_id = :project_filter)
               AND (CAST(:kind AS text) IS NULL OR kind = :kind)
               AND (CAST(:model_filter AS text) IS NULL OR model_id = :model_filter)
               AND (CAST(:agent_filter AS text) IS NULL OR agent_id = :agent_filter)
               AND (CAST(:session_filter AS text) IS NULL OR session_id = :session_filter)
               AND (CAST(:key_filter AS text) IS NULL OR api_key_id = :key_filter)
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public UsageRecordRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 一页明细。游标的两个键是上一页最后一条的发生时间与 ID。
     *
     * @param filter 筛选条件
     * @param range 时间范围
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 条
     */
    public List<UsageRecord> page(UsageRecordFilter filter, UsageRange range, PageRequest page) {
        Instant afterTime =
                page.after() == null ? null : Instant.parse(page.after().key(0));
        String afterId = page.after() == null ? null : page.after().key(1);
        return bind(sql(SELECT + """
                                   AND (CAST(:after_time AS timestamptz) IS NULL
                                        OR (occurred_at, id) < (CAST(:after_time AS timestamptz), :after_id))
                                 ORDER BY occurred_at DESC, id DESC
                                 LIMIT :limit
                                """), filter, range)
                .param("after_time", afterTime)
                .param("after_id", afterId)
                .param("limit", page.fetchSize())
                .query(UsageRecordRepository::map)
                .list();
    }

    /**
     * 按条件读出明细，新的在前，至多 {@code limit} 条（导出用）。
     *
     * @param filter 筛选条件
     * @param range 时间范围
     * @param limit 至多几条
     * @return 明细
     */
    public List<UsageRecord> list(UsageRecordFilter filter, UsageRange range, int limit) {
        return bind(sql(SELECT + """
                                 ORDER BY occurred_at DESC, id DESC
                                 LIMIT :limit
                                """), filter, range)
                .param("limit", limit)
                .query(UsageRecordRepository::map)
                .list();
    }

    private static ScopedStatement bind(ScopedStatement statement, UsageRecordFilter filter, UsageRange range) {
        return statement
                .param("from", range.from())
                .param("to", range.to())
                .param("project_filter", filter.projectId())
                .param("kind", filter.kind() == null ? null : filter.kind().value())
                .param("model_filter", filter.modelId())
                .param("agent_filter", filter.agentId())
                .param("session_filter", filter.sessionId())
                .param("key_filter", filter.apiKeyId());
    }

    private static UsageRecord map(ResultSet rs, int row) throws SQLException {
        int version = rs.getInt("price_book_version");
        return new UsageRecord(
                rs.getString("id"),
                JdbcValues.instant(rs, "occurred_at"),
                UsageKind.of(rs.getString("kind")),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("api_key_id"),
                rs.getString("agent_id"),
                rs.getString("agent_version_id"),
                rs.getString("session_id"),
                rs.getString("thread_id"),
                rs.getString("turn_id"),
                rs.getString("request_id"),
                rs.getString("model_id"),
                rs.getString("provider_account_id"),
                rs.getString("upstream_model"),
                rs.getString("purpose"),
                rs.getBoolean("fallback"),
                rs.getString("usage_source"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("cache_read_tokens"),
                rs.getLong("cache_write_tokens"),
                rs.getLong("reasoning_tokens"),
                rs.getBigDecimal("quantity"),
                rs.getString("price_book_id"),
                rs.wasNull() ? null : version,
                rs.getString("currency"),
                rs.getBigDecimal("cost"),
                JsonMappers.lenient().readValue(rs.getString("attributes"), ATTRIBUTES));
    }
}
