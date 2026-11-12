package cn.refinex.meridian.controlplane.metering;

import static cn.refinex.meridian.controlplane.support.LedgerStreams.await;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.count;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.db;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.fields;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.publish;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.sample;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.controlplane.metering.app.LedgerPartitions;
import cn.refinex.meridian.controlplane.metering.infra.system.UsageLedgerRepository;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 用量账本（T16-A01、A02、A07）：Stream {@code usage} 的消息按价格表版本计价入账、重投幂等、调价不影响历史，沙箱与搜索的种类，坏消息
 * 进死信不挡后续，分区提前创建与保留。消息取 runtime 的契约样本（{@code meridian-test-support}），替换租户等取值后发布。
 */
class UsageLedgerIT extends ControlPlaneTestBase {

    @Autowired
    RedisMessaging redis;

    @Autowired
    UsageLedgerRepository ledger;

    @Test
    void modelUsageIsPricedByItsPriceBookVersionAndRedeliveryIsIdempotent() {
        TenantFixture fixture = TenantFixture.create(client());
        String payload = modelUsage(fixture, 1, "2026-11-12T08:00:00Z");

        String id = publish(redis, "usage", payload);
        await("入账", () -> count("usage_records", id) == 1);
        // runtime 的 Relay 在发布后、标记前崩溃：同一条消息再投递两次
        publish(redis, "usage", id, payload);
        publish(redis, "usage", id, payload);
        String marker = publish(redis, "usage", modelUsage(fixture, 1, "2026-11-12T08:00:01Z"));
        await("后一条入账", () -> count("usage_records", marker) == 1);

        assertThat(count("usage_records", id)).isEqualTo(1);
        Map<String, Object> row = db().sql("SELECT * FROM cp.usage_records WHERE id = :id")
                .param("id", id)
                .query()
                .singleRow();
        // 未命中缓存的输入 900×0.8 + 缓存读 200×0.16 + 缓存写 100×0.8 + 非推理输出 250×2 + 推理 50×8 = 1732，每百万 token
        assertThat((BigDecimal) row.get("cost")).isEqualByComparingTo("0.001732");
        assertThat(((BigDecimal) row.get("cost")).scale()).isEqualTo(12);
        assertThat(row.get("currency")).isEqualTo("CNY");
        assertThat(row.get("price_book_version")).isEqualTo(1);
        assertThat((BigDecimal) row.get("quantity")).isEqualByComparingTo("1500");
        assertThat(row.get("kind")).isEqualTo("model_tokens");
        // 账本汇总只计一次：两条记录各 1500（输入加输出）
        assertThat(budgetUsage(fixture.get("tenant"), "tenant", fixture.get("tenant"), "2026-11"))
                .isEqualTo(3000);
        assertThat(budgetUsage(fixture.get("tenant"), "session", "ses_sample", "total"))
                .isEqualTo(3000);
    }

    @Test
    void anotherPriceBookVersionPricesNewRecordsAndLeavesHistoryAlone() {
        TenantFixture fixture = TenantFixture.create(client());
        String before = publish(redis, "usage", modelUsage(fixture, 1, "2026-11-12T08:00:00Z"));
        await("调价前的记录入账", () -> count("usage_records", before) == 1);

        assertThat(client().admin(
                                "POST",
                                "/admin/v1/tenants/" + fixture.get("tenant") + "/price-books/"
                                        + fixture.get("price_book") + "/versions",
                                token("owner"),
                                "{\"prices\":{\"input\":\"1.6\",\"output\":\"4\",\"cache_read\":\"0.32\","
                                        + "\"cache_write\":\"1.6\",\"reasoning\":\"16\"}}")
                        .status())
                .isEqualTo(201);
        // runtime 在结算时带上当时的版本：调价之后结算的用量是版本 2
        String after = publish(redis, "usage", modelUsage(fixture, 2, "2026-11-12T09:00:00Z"));
        await("调价后的记录入账", () -> count("usage_records", after) == 1);

        assertThat(cost(before)).isEqualByComparingTo("0.001732");
        assertThat(cost(after)).isEqualByComparingTo("0.003464");
    }

    @Test
    void sandboxSearchStorageAndEmbeddingUsageAreRecordedWithTheirQuantities() {
        TenantFixture fixture = TenantFixture.create(client());
        String tenant = fixture.get("tenant");
        String project = fixture.get("project");
        String sandbox = publish(
                redis,
                "usage",
                sample("usage", "sandbox_vcpu_seconds", fields("tenant_id", tenant, "project_id", project)));
        String search = publish(
                redis, "usage", sample("usage", "search_calls", fields("tenant_id", tenant, "project_id", project)));
        // knowledge 的知识库存储量（ADR-0051）
        String storage = publish(
                redis,
                "usage",
                sample("usage", "knowledge_storage_gb_days", fields("tenant_id", tenant, "project_id", project)));
        String embedding = publish(
                redis,
                "usage",
                sample(
                        "usage",
                        "embedding_tokens",
                        fields(
                                "tenant_id",
                                tenant,
                                "project_id",
                                project,
                                "model_id",
                                fixture.get("model"),
                                "price_book_id",
                                fixture.get("price_book"))));
        await(
                "四条入账",
                () -> count("usage_records", embedding)
                                + count("usage_records", sandbox)
                                + count("usage_records", search)
                                + count("usage_records", storage)
                        == 4);

        assertThat(quantity(sandbox)).isEqualByComparingTo("12.5");
        assertThat(cost(sandbox)).as("沙箱没有单价").isNull();
        assertThat(db().sql("SELECT attributes->>'size' FROM cp.usage_records WHERE id = :id")
                        .param("id", sandbox)
                        .query(String.class)
                        .single())
                .isEqualTo("small");
        assertThat(quantity(search)).isEqualByComparingTo("1");
        assertThat(quantity(storage)).isEqualByComparingTo("1.75");
        assertThat(cost(storage)).as("存储没有单价").isNull();
        assertThat(db().sql("SELECT attributes->>'knowledge_base_id' FROM cp.usage_records WHERE id = :id")
                        .param("id", storage)
                        .query(String.class)
                        .single())
                .isEqualTo("kb_sample");
        // Embedding 只有输入：640 × 0.8 / 1e6
        assertThat(cost(embedding)).isEqualByComparingTo("0.000512");
        assertThat(budgetUsage(tenant, "project", project, "2026-11"))
                .as("只有模型 token 计入预算")
                .isEqualTo(640);
    }

    @Test
    void aMalformedMessageIsDeadLetteredAndDoesNotBlockTheRest() {
        TenantFixture fixture = TenantFixture.create(client());
        String broken = publish(
                redis,
                "usage",
                "{\"kind\":\"galaxy_tokens\",\"tenant_id\":\"" + fixture.get("tenant")
                        + "\",\"occurred_at\":\"2026-11-12T08:00:00Z\"}");
        String good = publish(redis, "usage", modelUsage(fixture, 1, "2026-11-12T08:00:00Z"));

        await("后面的消息入账", () -> count("usage_records", good) == 1);
        await(
                "坏消息进死信",
                () -> db().sql("SELECT count(*) FROM cp.stream_dead_letters WHERE message_id = :id")
                                .param("id", broken)
                                .query(Long.class)
                                .single()
                        == 1);
        assertThat(db().sql("SELECT stream || ' ' || error FROM cp.stream_dead_letters WHERE message_id = :id")
                        .param("id", broken)
                        .query(String.class)
                        .single())
                .startsWith("usage ")
                .contains("galaxy_tokens");
    }

    @Test
    void nextMonthsPartitionsExistBeforeTheMonthStartsAndOldOnesAreDropped() {
        // 时钟推进到月底：下个月的两张分区表已经存在
        MutableClock clock = MutableClock.at(Instant.parse("2027-02-28T23:59:59Z"));
        new LedgerPartitions(ledger, clock).maintain();

        assertThat(partitions())
                .contains(
                        "usage_records_p2027_02",
                        "usage_records_p2027_03",
                        "audit_logs_p2027_02",
                        "audit_logs_p2027_03");
        clock.advance(java.time.Duration.ofSeconds(1));
        assertThat(new LedgerPartitions(ledger, clock).maintain().created())
                .as("3 月已提前建好，只新建 4 月的两张")
                .isEqualTo(2);

        // 保留 3 年：早于 36 个月的分区被删除（截止月份按数据库时间）
        ledger.ensurePartitions(LocalDate.of(2020, 1, 1));
        assertThat(partitions()).contains("usage_records_p2020_01", "audit_logs_p2020_01");
        assertThat(ledger.dropExpiredPartitions()).isGreaterThanOrEqualTo(2);
        assertThat(partitions()).doesNotContain("usage_records_p2020_01", "audit_logs_p2020_01");
        assertThat(partitions()).contains("usage_records_p2027_03");
    }

    private static String modelUsage(TenantFixture fixture, int priceVersion, String occurredAt) {
        return sample(
                "usage",
                "model_tokens",
                fields(
                        "tenant_id",
                        fixture.get("tenant"),
                        "project_id",
                        fixture.get("project"),
                        "api_key_id",
                        fixture.get("key"),
                        "model_id",
                        fixture.get("model"),
                        "price_book_id",
                        fixture.get("price_book"),
                        "price_book_version",
                        priceVersion,
                        "occurred_at",
                        occurredAt));
    }

    private static BigDecimal cost(String id) {
        return db().sql("SELECT cost FROM cp.usage_records WHERE id = :id")
                .param("id", id)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
    }

    private static BigDecimal quantity(String id) {
        return db().sql("SELECT quantity FROM cp.usage_records WHERE id = :id")
                .param("id", id)
                .query(BigDecimal.class)
                .single();
    }

    static long budgetUsage(String tenant, String level, String scope, String period) {
        return db().sql("""
                        SELECT coalesce(sum(used_tokens), 0) FROM cp.budget_usage
                         WHERE tenant_id = :tenant AND level = :level AND scope_id = :scope AND period = :period
                        """)
                .param("tenant", tenant)
                .param("level", level)
                .param("scope", scope)
                .param("period", period)
                .query(Long.class)
                .single();
    }

    private static List<String> partitions() {
        return db().sql("""
                        SELECT c.relname FROM pg_inherits i
                          JOIN pg_class c ON c.oid = i.inhrelid
                          JOIN pg_class p ON p.oid = i.inhparent
                          JOIN pg_namespace n ON n.oid = p.relnamespace
                         WHERE n.nspname = 'cp' AND p.relname IN ('usage_records', 'audit_logs')
                        """).query(String.class).list();
    }
}
