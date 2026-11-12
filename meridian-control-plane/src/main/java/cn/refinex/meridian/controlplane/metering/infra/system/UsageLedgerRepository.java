package cn.refinex.meridian.controlplane.metering.infra.system;

import cn.refinex.meridian.controlplane.catalog.domain.TokenPrices;
import cn.refinex.meridian.controlplane.metering.domain.UsageRecord;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 用量账本的跨租户仓储（ADR-0048 第 3、7 条）：用量消费者按 {@code (id, occurred_at)} 幂等入账，查找价格表版本与智能体，写死信；
 * 分区维护经迁移角色拥有的函数创建与删除分区。
 */
@Repository
@CrossTenant(reason = "用量消费者与分区维护是跨租户的后台作业（架构设计「租户隔离」中 SystemRepository 一行）")
public class UsageLedgerRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public UsageLedgerRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 入账一条记录；同一 {@code (id, occurred_at)} 已存在时什么都不做。
     *
     * @param record 记录
     * @param recordedAt 入账时间
     * @return 真正插入时为 true
     */
    public boolean insert(UsageRecord record, Instant recordedAt) {
        return jdbc().sql("""
                        INSERT INTO cp.usage_records (id, occurred_at, kind, tenant_id, project_id, api_key_id, agent_id,
                                                      agent_version_id, session_id, thread_id, turn_id, request_id, model_id,
                                                      provider_account_id, upstream_model, purpose, fallback, usage_source,
                                                      input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
                                                      reasoning_tokens, quantity, price_book_id, price_book_version, currency,
                                                      cost, attributes, recorded_at)
                        VALUES (:id, :occurred_at, :kind, :tenant, :project, :api_key, :agent, :agent_version, :session,
                                :thread, :turn, :request, :model, :provider_account, :upstream_model, :purpose, :fallback,
                                :usage_source, :input, :output, :cache_read, :cache_write, :reasoning, :quantity,
                                :price_book, :price_book_version, :currency, :cost, :attributes, :recorded_at)
                        ON CONFLICT (id, occurred_at) DO NOTHING
                        """)
                        .param("id", record.id())
                        .param("occurred_at", JdbcValues.timestamptz(record.occurredAt()))
                        .param("kind", record.kind().value())
                        .param("tenant", record.tenantId())
                        .param("project", record.projectId())
                        .param("api_key", record.apiKeyId())
                        .param("agent", record.agentId())
                        .param("agent_version", record.agentVersionId())
                        .param("session", record.sessionId())
                        .param("thread", record.threadId())
                        .param("turn", record.turnId())
                        .param("request", record.requestId())
                        .param("model", record.modelId())
                        .param("provider_account", record.providerAccountId())
                        .param("upstream_model", record.upstreamModel())
                        .param("purpose", record.purpose())
                        .param("fallback", record.fallback())
                        .param("usage_source", record.usageSource())
                        .param("input", record.inputTokens())
                        .param("output", record.outputTokens())
                        .param("cache_read", record.cacheReadTokens())
                        .param("cache_write", record.cacheWriteTokens())
                        .param("reasoning", record.reasoningTokens())
                        .param("quantity", record.quantity())
                        .param("price_book", record.priceBookId())
                        .param("price_book_version", record.priceBookVersion())
                        .param("currency", record.currency())
                        .param("cost", record.cost())
                        .param("attributes", JdbcValues.jsonbOf(record.attributes()))
                        .param("recorded_at", JdbcValues.timestamptz(recordedAt))
                        .update()
                == 1;
    }

    /**
     * 价格表的一个版本，必须属于给出的租户。
     *
     * @param tenantId 租户 ID
     * @param priceBookId 价格表 ID
     * @param version 版本号
     * @return 币种与单价；不存在或不属于该租户时为空
     */
    public Optional<PricedVersion> priceVersion(String tenantId, String priceBookId, int version) {
        return jdbc().sql("""
                        SELECT b.currency, v.input_price, v.output_price, v.cache_read_price, v.cache_write_price,
                               v.reasoning_price
                          FROM cp.price_book_versions v
                          JOIN cp.price_books b ON b.id = v.price_book_id
                         WHERE v.price_book_id = :id AND v.version = :version AND b.tenant_id = :tenant
                        """)
                .param("id", priceBookId)
                .param("version", version)
                .param("tenant", tenantId)
                .query((rs, row) -> new PricedVersion(
                        rs.getString("currency"),
                        new TokenPrices(
                                rs.getBigDecimal("input_price"),
                                rs.getBigDecimal("output_price"),
                                rs.getBigDecimal("cache_read_price"),
                                rs.getBigDecimal("cache_write_price"),
                                rs.getBigDecimal("reasoning_price"))))
                .optional();
    }

    /**
     * 智能体版本所属的智能体。
     *
     * @param agentVersionId 智能体版本 ID
     * @return 智能体 ID；版本不在 control-plane（例如开发配置中的版本）时为空
     */
    public Optional<String> agentOf(String agentVersionId) {
        return jdbc().sql("SELECT agent_id FROM cp.agent_versions WHERE id = :id")
                .param("id", agentVersionId)
                .query(String.class)
                .optional();
    }

    /**
     * 确保某个 UTC 月份的 {@code usage_records} 与 {@code audit_logs} 分区存在（{@code cp.ensure_ledger_partitions}）。
     *
     * @param month 月份中的任意一天
     * @return 新建的分区数
     */
    public int ensurePartitions(LocalDate month) {
        return jdbc().sql("SELECT cp.ensure_ledger_partitions(:month)")
                .param("month", month)
                .query(Integer.class)
                .single();
    }

    /**
     * 删除超过保留期（36 个月，按数据库时间）的分区（{@code cp.drop_expired_ledger_partitions}）。
     *
     * @return 删除的分区数
     */
    public int dropExpiredPartitions() {
        return jdbc().sql("SELECT cp.drop_expired_ledger_partitions()")
                .query(Integer.class)
                .single();
    }

    /**
     * 写一条死信。
     *
     * @param stream Stream 的键
     * @param recordId Redis 条目 ID
     * @param messageId outbox 消息 ID；可为空
     * @param payload 载荷原文；可为空
     * @param error 失败原因
     * @param now 当前时间
     */
    public void deadLetter(
            String stream, String recordId, String messageId, String payload, String error, Instant now) {
        jdbc().sql("""
                        INSERT INTO cp.stream_dead_letters (stream, record_id, message_id, payload, error, created_at)
                        VALUES (:stream, :record, :message, :payload, :error, :now)
                        """)
                .param("stream", stream)
                .param("record", recordId)
                .param("message", messageId)
                .param("payload", payload)
                .param("error", error)
                .param("now", JdbcValues.timestamptz(now))
                .update();
    }

    /**
     * 价格表版本的币种与单价。
     *
     * @param currency 币种
     * @param prices 单价
     */
    public record PricedVersion(String currency, TokenPrices prices) {}
}
