package cn.refinex.meridian.controlplane.budget;

import static cn.refinex.meridian.controlplane.support.LedgerStreams.await;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.count;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.db;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.fields;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.publish;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.sample;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.budget.BudgetKeys;
import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.controlplane.budget.app.BudgetLimitSync;
import cn.refinex.meridian.controlplane.budget.app.BudgetReconciliation;
import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 四级预算（T16-A03 的软阈值、A04、A08 的 control-plane 侧）：新租户在设置预算之前没有任何限额（runtime 按 0 处理），预算的增删改
 * 在提交后同步到 Redis 的限额哈希，全量同步能恢复丢失的哈希；账本越过软阈值产生一条告警与审计；对账以"账本加未过期预扣"修正
 * Redis 计数并记录差异。
 */
class BudgetIT extends ControlPlaneTestBase {

    @Autowired
    RedisMessaging redis;

    @Autowired
    StringRedisTemplate template;

    @Autowired
    BudgetLimitSync limitSync;

    @Autowired
    BudgetReconciliation reconciliation;

    @Autowired
    MeterRegistry meters;

    @Test
    void aNewTenantHasNoLimitsUntilAnAdministratorSetsThem() {
        String tenant = client().admin(
                        "POST",
                        "/admin/v1/tenants",
                        token(PLATFORM),
                        "{\"name\":\"零预算租户\",\"owner\":{\"subject\":\"owner\"}}")
                .text("id");
        String limits = BudgetKeys.limits(tenant);
        limitSync.syncAll();
        assertThat(template.hasKey(limits)).as("没有预算：哈希不存在，runtime 把每一级都当作 0").isFalse();
        String project = client().admin(
                        "POST", "/admin/v1/tenants/" + tenant + "/projects", token("owner"), "{\"name\":\"P\"}")
                .text("id");

        Reply tenantBudget =
                create(tenant, "{\"level\":\"tenant\",\"soft_limit_tokens\":800000,\"hard_limit_tokens\":1000000}");
        assertThat(tenantBudget.status()).isEqualTo(201);
        assertThat(tenantBudget.text("period")).isEqualTo("monthly");
        assertThat(tenantBudget.json().get("used_tokens").asLong()).isZero();
        create(tenant, "{\"level\":\"project\",\"hard_limit_tokens\":null}");
        create(tenant, "{\"level\":\"api_key\",\"hard_limit_tokens\":null}");
        Reply sessionDefault = create(tenant, "{\"level\":\"session\",\"hard_limit_tokens\":5000}");
        assertThat(sessionDefault.text("period")).isEqualTo("total");
        assertThat(sessionDefault.json().get("used_tokens").isNull())
                .as("默认值不对应具体范围")
                .isTrue();
        Reply projectBudget =
                create(tenant, "{\"level\":\"project\",\"scope_id\":\"" + project + "\",\"hard_limit_tokens\":100}");
        assertThat(projectBudget.text("project_id")).isEqualTo(project);

        assertThat(hash(limits))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "tenant",
                        "1000000",
                        "project:*",
                        "-1",
                        "api_key:*",
                        "-1",
                        "session:*",
                        "5000",
                        "project:" + project,
                        "100"));

        String projectBudgetId = projectBudget.text("id");
        assertThat(client().admin(
                                "PUT",
                                "/admin/v1/tenants/" + tenant + "/budgets/" + projectBudgetId,
                                token("owner"),
                                "{\"soft_limit_tokens\":null,\"hard_limit_tokens\":200}")
                        .status())
                .isEqualTo(200);
        assertThat(hash(limits)).containsEntry("project:" + project, "200");
        assertThat(client().admin(
                                "DELETE",
                                "/admin/v1/tenants/" + tenant + "/budgets/" + projectBudgetId,
                                token("owner"),
                                null)
                        .status())
                .isEqualTo(204);
        assertThat(hash(limits)).doesNotContainKey("project:" + project).containsEntry("project:*", "-1");

        // Redis 丢了限额：全量同步按数据库重写
        template.delete(limits);
        limitSync.syncAll();
        assertThat(hash(limits)).containsEntry("tenant", "1000000").containsEntry("session:*", "5000");

        // 变更有审计
        await(
                "预算的审计入库",
                () -> db().sql("""
                                SELECT count(*) FROM cp.audit_logs
                                 WHERE tenant_id = :tenant AND action IN ('budget.created', 'budget.updated', 'budget.deleted')
                                """).param("tenant", tenant).query(Long.class).single() == 7);
    }

    @Test
    void invalidBudgetsAreRejected() {
        TenantFixture fixture = TenantFixture.create(client());
        String tenant = fixture.get("tenant");

        assertThat(create(tenant, "{\"level\":\"tenant\",\"scope_id\":\"" + tenant + "\",\"hard_limit_tokens\":1}")
                        .text("code"))
                .isEqualTo("budget.invalid");
        assertThat(create(tenant, "{\"level\":\"project\",\"soft_limit_tokens\":10,\"hard_limit_tokens\":5}")
                        .text("code"))
                .isEqualTo("budget.invalid");
        assertThat(create(tenant, "{\"level\":\"session\",\"scope_id\":\"not-a-session\",\"hard_limit_tokens\":5}")
                        .text("code"))
                .isEqualTo("budget.invalid");
        assertThat(create(
                                tenant,
                                "{\"level\":\"api_key\",\"scope_id\":\"key_01JD000000000000000000MISS\","
                                        + "\"hard_limit_tokens\":5}")
                        .status())
                .isEqualTo(404);
        Reply keyBudget = create(
                tenant, "{\"level\":\"api_key\",\"scope_id\":\"" + fixture.get("key") + "\",\"hard_limit_tokens\":5}");
        assertThat(keyBudget.text("project_id")).as("API Key 级记下 Key 所在的项目").isEqualTo(fixture.get("project"));
        assertThat(create(tenant, "{\"level\":\"tenant\",\"hard_limit_tokens\":1}")
                        .text("code"))
                .as("夹具已有租户级预算")
                .isEqualTo("budget.exists");
    }

    @Test
    void crossingTheSoftLimitRaisesOneAlertAndAnAudit() {
        TenantFixture fixture = TenantFixture.create(client());
        String tenant = fixture.get("tenant");
        String project = fixture.get("project");
        create(
                tenant,
                "{\"level\":\"project\",\"scope_id\":\"" + project
                        + "\",\"soft_limit_tokens\":2000,\"hard_limit_tokens\":10000}");
        String occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        List<String> ids = List.of(
                publish(redis, "usage", usage(fixture, occurredAt)),
                publish(redis, "usage", usage(fixture, occurredAt)),
                publish(redis, "usage", usage(fixture, occurredAt)));
        await("三条入账", () -> ids.stream().allMatch(id -> count("usage_records", id) == 1));

        Reply alerts = client().admin("GET", "/admin/v1/tenants/" + tenant + "/budget-alerts", token("admin"), null);
        assertThat(alerts.status()).isEqualTo(200);
        assertThat(alerts.json().get("data")).hasSize(1);
        var alert = alerts.json().get("data").get(0);
        assertThat(alert.get("level").asString()).isEqualTo("project");
        assertThat(alert.get("scope_id").asString()).isEqualTo(project);
        assertThat(alert.get("kind").asString()).isEqualTo("soft_limit_reached");
        assertThat(alert.get("threshold_tokens").asLong()).isEqualTo(2000);
        assertThat(alert.get("used_tokens").asLong()).as("第二条越过：1500 → 3000").isEqualTo(3000);
        await(
                "告警的审计入库",
                () -> client().admin(
                                        "GET",
                                        "/admin/v1/tenants/" + tenant + "/audit-logs?action=budget.soft_limit_reached",
                                        token("owner"),
                                        null)
                                .json()
                                .get("data")
                                .size()
                        == 1);
    }

    @Test
    void projectBudgetStatusFollowsTheEffectiveLimitAndTheLedger() {
        TenantFixture fixture = TenantFixture.create(client());
        String tenant = fixture.get("tenant");
        String project = fixture.get("project");
        String path = "/admin/v1/tenants/" + tenant + "/projects/" + project + "/budget-status";

        // 没有项目级预算：硬阈值 0，调用会被拒绝（ADR-0048）；开发者与观察者也能看
        Reply none = client().admin("GET", path, token("viewer"), null);
        assertThat(none.status()).isEqualTo(200);
        assertThat(none.text("limit_source")).isEqualTo("none");
        assertThat(none.json().get("hard_limit_tokens").asLong()).isZero();
        assertThat(none.text("state")).isEqualTo("hard_exceeded");
        assertThat(none.text("period")).isEqualTo(BudgetKeys.month(Instant.now()));

        // 项目级默认值
        create(tenant, "{\"level\":\"project\",\"soft_limit_tokens\":1000,\"hard_limit_tokens\":100000}");
        Reply byDefault = client().admin("GET", path, token("developer"), null);
        assertThat(byDefault.text("limit_source")).isEqualTo("default");
        assertThat(byDefault.json().get("soft_limit_tokens").asLong()).isEqualTo(1000);
        assertThat(byDefault.text("state")).isEqualTo("ok");

        // 本项目自己的预算优先；入账 1500 token 后越过软阈值，已用与账本汇总一致
        create(
                tenant,
                "{\"level\":\"project\",\"scope_id\":\"" + project
                        + "\",\"soft_limit_tokens\":1200,\"hard_limit_tokens\":null}");
        String id = publish(
                redis,
                "usage",
                usage(fixture, Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()));
        await("入账", () -> count("usage_records", id) == 1);
        Reply own = client().admin("GET", path, token("owner"), null);
        assertThat(own.text("limit_source")).isEqualTo("project");
        assertThat(own.json().get("hard_limit_tokens").isNull()).as("不限").isTrue();
        long ledger = db().sql("""
                        SELECT COALESCE(sum(input_tokens + output_tokens), 0) FROM cp.usage_records
                         WHERE tenant_id = :t AND project_id = :p AND kind = 'model_tokens' AND occurred_at >= :from
                        """)
                .param("t", tenant)
                .param("p", project)
                .param("from", java.sql.Timestamp.from(Instant.parse(own.text("period_start"))))
                .query(Long.class)
                .single();
        assertThat(own.json().get("used_tokens").asLong()).isEqualTo(ledger).isEqualTo(1500);
        assertThat(own.text("state")).isEqualTo("soft_exceeded");
        assertThat(client().admin("GET", path, token("approver"), null).status())
                .isEqualTo(403);
    }

    @Test
    void reconciliationCorrectsRedisToTheLedgerPlusLiveReservations() {
        TenantFixture fixture = TenantFixture.create(client());
        String tenant = fixture.get("tenant");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String id = publish(redis, "usage", usage(fixture, now.toString()));
        await("入账", () -> count("usage_records", id) == 1);
        String month = BudgetKeys.month(now);
        String tenantKey = BudgetKeys.used(tenant, BudgetLevel.TENANT, tenant, month);
        String orphanKey = BudgetKeys.used(tenant, BudgetLevel.PROJECT, fixture.get("other_project"), month);
        // 人为制造不一致：计数偏多、一个账本里没有的计数、一笔进行中的预扣、一个已过期的预扣 ID
        template.opsForValue().set(tenantKey, "9999");
        template.opsForValue().set(orphanKey, "777");
        String live = Ids.newUlid() + "@" + month;
        template.opsForHash().putAll(BudgetKeys.reservation(tenant, live), Map.of("amount", "300", "keys", tenantKey));
        template.opsForSet().add(BudgetKeys.reservations(tenant), live, "expired@" + month);
        double before = corrections();

        BudgetReconciliation.Result result = reconciliation.reconcile();

        assertThat(result.ran()).isTrue();
        assertThat(template.opsForValue().get(tenantKey))
                .as("账本 1500 加进行中的预扣 300")
                .isEqualTo("1800");
        assertThat(template.opsForValue().get(orphanKey)).isEqualTo("0");
        assertThat(template.opsForSet().members(BudgetKeys.reservations(tenant)))
                .containsExactly(live);
        List<Map<String, Object>> rows =
                db().sql("""
                        SELECT level, scope_id, observed_tokens, inflight_tokens, ledger_tokens, correction_tokens
                          FROM cp.budget_corrections WHERE tenant_id = :tenant ORDER BY level
                        """).param("tenant", tenant).query().listOfRows();
        assertThat(rows)
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("level", "tenant")
                        .containsEntry("observed_tokens", 9999L)
                        .containsEntry("inflight_tokens", 300L)
                        .containsEntry("ledger_tokens", 1500L)
                        .containsEntry("correction_tokens", -8199L))
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("scope_id", fixture.get("other_project"))
                        .containsEntry("correction_tokens", -777L));
        assertThat(corrections()).isGreaterThan(before);

        // 没有新的不一致时，再次对账不修正这个租户
        reconciliation.reconcile();
        assertThat(db().sql("SELECT count(*) FROM cp.budget_corrections WHERE tenant_id = :tenant")
                        .param("tenant", tenant)
                        .query(Long.class)
                        .single())
                .isEqualTo(rows.size());
    }

    private double corrections() {
        return meters.find(BudgetReconciliation.CORRECTIONS).counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
    }

    private Reply create(String tenant, String body) {
        return client().admin("POST", "/admin/v1/tenants/" + tenant + "/budgets", token("owner"), body);
    }

    private Map<Object, Object> hash(String key) {
        return template.opsForHash().entries(key);
    }

    private static String usage(TenantFixture fixture, String occurredAt) {
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
                        "session_id",
                        Ids.newId(IdPrefix.SESSION),
                        "model_id",
                        fixture.get("model"),
                        "price_book_id",
                        fixture.get("price_book"),
                        "occurred_at",
                        occurredAt));
    }
}
