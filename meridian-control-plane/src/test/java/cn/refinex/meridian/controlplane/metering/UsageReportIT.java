package cn.refinex.meridian.controlplane.metering;

import static cn.refinex.meridian.controlplane.support.LedgerStreams.await;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.count;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.db;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.fields;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.publish;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.sample;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 用量报表（T16-A06）：{@code GET /v1/usage} 与管理接口的租户、项目报表的聚合结果与账本明细一致。期望值由测试直接从
 * {@code cp.usage_records} 的明细按同样的维度分组算出，不经被测代码。
 */
class UsageReportIT extends ControlPlaneTestBase {

    /** 维度在报表行中的字段名。 */
    private static final Map<String, String> FIELD = Map.of(
            "day", "day", "project", "project_id", "model", "model_id", "agent", "agent_id", "api_key", "api_key_id");

    @Autowired
    RedisMessaging redis;

    @Test
    void reportsMatchTheLedgerDetails() {
        TenantFixture fixture = TenantFixture.create(client()).withAgentConfig(client());
        String tenant = fixture.get("tenant");
        String project = fixture.get("project");
        String agentVersion = client().admin(
                        "GET",
                        "/admin/v1/tenants/" + tenant + "/projects/" + project + "/agents/" + fixture.get("agent")
                                + "/versions/1",
                        token("owner"),
                        null)
                .text("id");
        Instant day0 = Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .minus(3, ChronoUnit.DAYS)
                .plusSeconds(3600);
        Instant day1 = day0.plus(1, ChronoUnit.DAYS);
        Instant day2 = day1.plus(1, ChronoUnit.DAYS);
        List<String> ids = new ArrayList<>();
        ids.add(publish(
                redis, "usage", model(fixture, project, fixture.get("model"), fixture.get("key"), agentVersion, day0)));
        ids.add(publish(redis, "usage", model(fixture, project, fixture.get("spare_model"), null, null, day0)));
        ids.add(publish(
                redis, "usage", model(fixture, project, fixture.get("model"), fixture.get("key"), agentVersion, day1)));
        ids.add(publish(
                redis, "usage", model(fixture, fixture.get("other_project"), fixture.get("model"), null, null, day1)));
        ids.add(publish(
                redis,
                "usage",
                sample(
                        "usage",
                        "embedding_tokens",
                        fields(
                                "tenant_id", tenant,
                                "project_id", project,
                                "model_id", fixture.get("model"),
                                "price_book_id", fixture.get("price_book"),
                                "occurred_at", day1.toString()))));
        ids.add(publish(
                redis,
                "usage",
                sample(
                        "usage",
                        "sandbox_vcpu_seconds",
                        fields("tenant_id", tenant, "project_id", project, "occurred_at", day2.toString()))));
        ids.add(publish(
                redis,
                "usage",
                sample(
                        "usage",
                        "search_calls",
                        fields(
                                "tenant_id",
                                tenant,
                                "project_id",
                                project,
                                "api_key_id",
                                fixture.get("key"),
                                "occurred_at",
                                day2.toString()))));
        await("七条入账", () -> ids.stream().allMatch(id -> count("usage_records", id) == 1));
        assertThat(db().sql("SELECT count(*) FROM cp.usage_records WHERE tenant_id = :t AND agent_id = :a")
                        .param("t", tenant)
                        .param("a", fixture.get("agent"))
                        .query(Long.class)
                        .single())
                .as("按智能体版本补上了智能体")
                .isEqualTo(2);

        String from = day0.truncatedTo(ChronoUnit.DAYS).toString();
        String to = day2.plus(1, ChronoUnit.DAYS).toString();
        List<Map<String, Object>> details = details(tenant);
        List<Map<String, Object>> projectDetails = details.stream()
                .filter(row -> project.equals(row.get("project_id")))
                .toList();
        String range = "from=" + from + "&to=" + to;

        // 公开接口：API Key 所在的项目
        for (List<String> dims : List.of(
                List.<String>of(),
                List.of("day"),
                List.of("model"),
                List.of("agent"),
                List.of("api_key"),
                List.of("day", "model", "agent", "api_key"))) {
            Reply reply = client().api("GET", "/v1/usage?" + range + groupBy(dims), fixture.get("key_plaintext"), null);
            assertThat(reply.status()).as(reply.raw()).isEqualTo(200);
            assertThat(actual(reply.json(), dims))
                    .as("GET /v1/usage " + dims)
                    .isEqualTo(expected(projectDetails, dims));
        }
        // 管理接口：租户报表（全部项目、按项目过滤）与项目报表（观察者也能看）
        for (List<String> dims : List.of(List.of("project"), List.of("project", "model", "day"), List.<String>of())) {
            Reply reply = client().admin(
                            "GET",
                            "/admin/v1/tenants/" + tenant + "/usage?" + range + groupBy(dims),
                            token("admin"),
                            null);
            assertThat(reply.status()).as(reply.raw()).isEqualTo(200);
            assertThat(actual(reply.json(), dims)).as("租户报表 " + dims).isEqualTo(expected(details, dims));
        }
        Reply filtered = client().admin(
                        "GET",
                        "/admin/v1/tenants/" + tenant + "/usage?" + range + "&group_by=model&project_id=" + project,
                        token("owner"),
                        null);
        assertThat(actual(filtered.json(), List.of("model"))).isEqualTo(expected(projectDetails, List.of("model")));
        Reply projectReport = client().admin(
                        "GET",
                        "/admin/v1/tenants/" + tenant + "/projects/" + project + "/usage?" + range
                                + "&group_by=day,api_key",
                        token("viewer"),
                        null);
        assertThat(projectReport.status()).isEqualTo(200);
        assertThat(actual(projectReport.json(), List.of("day", "api_key")))
                .isEqualTo(expected(projectDetails, List.of("day", "api_key")));
    }

    @Test
    void invalidQueriesAreRejected() {
        TenantFixture fixture = TenantFixture.create(client());
        String key = fixture.get("key_plaintext");

        assertThat(client().api("GET", "/v1/usage?group_by=project", key, null).text("code"))
                .isEqualTo("usage.invalid_query");
        assertThat(client().api("GET", "/v1/usage?group_by=galaxy", key, null).text("code"))
                .isEqualTo("usage.invalid_query");
        assertThat(client().api("GET", "/v1/usage?from=2025-01-01T00:00:00Z&to=2026-06-01T00:00:00Z", key, null)
                        .text("code"))
                .isEqualTo("usage.invalid_query");
        assertThat(client().api("GET", "/v1/usage?from=2026-06-01T00:00:00Z&to=2026-06-01T00:00:00Z", key, null)
                        .status())
                .isEqualTo(400);
        // 没有用量：空报表，默认时间范围是本月第一天到现在
        Reply empty = client().api("GET", "/v1/usage", key, null);
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.json().get("rows")).isEmpty();
        assertThat(Instant.parse(empty.text("from")).atOffset(ZoneOffset.UTC).getDayOfMonth())
                .isEqualTo(1);
    }

    private static String model(
            TenantFixture fixture, String project, String model, String apiKey, String agentVersion, Instant at) {
        boolean priced = model.equals(fixture.get("model"));
        return sample(
                "usage",
                "model_tokens",
                fields(
                        "tenant_id",
                        fixture.get("tenant"),
                        "project_id",
                        project,
                        "api_key_id",
                        apiKey,
                        "agent_version_id",
                        agentVersion,
                        "model_id",
                        model,
                        "price_book_id",
                        priced ? fixture.get("price_book") : null,
                        "price_book_version",
                        priced ? 1 : null,
                        "occurred_at",
                        at.toString()));
    }

    private static List<Map<String, Object>> details(String tenant) {
        return db().sql("SELECT * FROM cp.usage_records WHERE tenant_id = :tenant")
                .param("tenant", tenant)
                .query()
                .listOfRows();
    }

    private static String groupBy(List<String> dims) {
        return dims.isEmpty() ? "" : "&group_by=" + String.join(",", dims);
    }

    /** 从明细按维度（再加种类与币种）分组求和：键是维度取值，值是各项合计的文本。 */
    private static Map<String, String> expected(List<Map<String, Object>> rows, List<String> dims) {
        Map<String, Totals> groups = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            List<String> key = new ArrayList<>();
            for (String dim : List.of("day", "project", "model", "agent", "api_key")) {
                if (!dims.contains(dim)) {
                    key.add("-");
                } else if (dim.equals("day")) {
                    key.add(((Timestamp) row.get("occurred_at"))
                            .toInstant()
                            .atOffset(ZoneOffset.UTC)
                            .toLocalDate()
                            .toString());
                } else {
                    key.add(String.valueOf(row.get(FIELD.get(dim))));
                }
            }
            key.add(String.valueOf(row.get("kind")));
            key.add(String.valueOf(row.get("currency")));
            groups.computeIfAbsent(String.join("|", key), k -> new Totals()).add(row);
        }
        Map<String, String> result = new TreeMap<>();
        groups.forEach((key, totals) -> result.put(key, totals.toString()));
        return result;
    }

    private static Map<String, String> actual(JsonNode report, List<String> dims) {
        Map<String, String> result = new TreeMap<>();
        for (JsonNode row : report.get("rows")) {
            List<String> key = new ArrayList<>();
            for (String dim : List.of("day", "project", "model", "agent", "api_key")) {
                JsonNode value = row.get(FIELD.get(dim));
                assertThat(value.isNull() == !dims.contains(dim) || dims.contains(dim))
                        .as("没有聚合的维度为 null：" + dim)
                        .isTrue();
                key.add(dims.contains(dim) ? (value.isNull() ? "null" : value.asString()) : "-");
            }
            key.add(row.get("kind").asString());
            key.add(row.get("currency").isNull() ? "null" : row.get("currency").asString());
            Totals totals = new Totals();
            totals.records = row.get("records").asLong();
            totals.quantity = new BigDecimal(row.get("quantity").asString());
            totals.input = row.get("input_tokens").asLong();
            totals.output = row.get("output_tokens").asLong();
            totals.cacheRead = row.get("cache_read_tokens").asLong();
            totals.cacheWrite = row.get("cache_write_tokens").asLong();
            totals.reasoning = row.get("reasoning_tokens").asLong();
            totals.cost = row.get("cost").isNull()
                    ? null
                    : new BigDecimal(row.get("cost").asString());
            result.put(String.join("|", key), totals.toString());
        }
        return result;
    }

    /** 一组明细的合计；金额与数量按数值比较（去掉末尾的 0）。 */
    private static final class Totals {
        long records;
        BigDecimal quantity = BigDecimal.ZERO;
        long input;
        long output;
        long cacheRead;
        long cacheWrite;
        long reasoning;
        BigDecimal cost;

        void add(Map<String, Object> row) {
            records++;
            quantity = quantity.add((BigDecimal) row.get("quantity"));
            input += ((Number) row.get("input_tokens")).longValue();
            output += ((Number) row.get("output_tokens")).longValue();
            cacheRead += ((Number) row.get("cache_read_tokens")).longValue();
            cacheWrite += ((Number) row.get("cache_write_tokens")).longValue();
            reasoning += ((Number) row.get("reasoning_tokens")).longValue();
            BigDecimal rowCost = (BigDecimal) row.get("cost");
            if (rowCost != null) {
                cost = cost == null ? rowCost : cost.add(rowCost);
            }
        }

        @Override
        public String toString() {
            return records + " " + quantity.stripTrailingZeros().toPlainString() + " " + input + " " + output + " "
                    + cacheRead + " " + cacheWrite + " " + reasoning + " "
                    + (cost == null ? "null" : cost.stripTrailingZeros().toPlainString());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Totals that && Objects.equals(toString(), that.toString());
        }

        @Override
        public int hashCode() {
            return toString().hashCode();
        }
    }
}
