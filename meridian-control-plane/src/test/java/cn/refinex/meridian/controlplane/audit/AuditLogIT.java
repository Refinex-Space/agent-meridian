package cn.refinex.meridian.controlplane.audit;

import static cn.refinex.meridian.controlplane.support.LedgerStreams.await;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.count;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.fields;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.publish;
import static cn.refinex.meridian.controlplane.support.LedgerStreams.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.LedgerStreams;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import cn.refinex.meridian.testsupport.TestDatabase;
import cn.refinex.meridian.testsupport.outbox.OutboxSamples;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * 审计（T16-A05）：管理操作、凭据访问、权限判定、待处理动作回应、沙箱命令（以及工具执行、轮次结束）都经审计消费者入库，能按时间、
 * 操作者、资源、动作检索；应用角色 {@code meridian_app} 不能更新或删除审计记录。runtime 的审计取契约样本（{@code meridian-test-support}）。
 */
class AuditLogIT extends ControlPlaneTestBase {

    @Autowired
    RedisMessaging redis;

    @Test
    void adminOperationsAndCredentialAccessAreSearchable() {
        TenantFixture fixture = TenantFixture.create(client());
        String tenant = fixture.get("tenant");
        String key = fixture.get("key");

        // 管理操作：创建 API Key（夹具）；凭据访问：runtime 解析凭据
        Reply resolved = client().internal(
                        "POST",
                        "/internal/v1/secrets:resolve",
                        serviceToken(),
                        "{\"tenant_id\":\"" + tenant + "\",\"project_id\":null,\"secret_id\":\"" + fixture.get("secret")
                                + "\"}");
        assertThat(resolved.status()).isEqualTo(200);

        JsonNode created = awaitOne(() -> search(tenant, "action=api_key.created&resource_id=" + key));
        assertThat(created.get("actor").get("type").asString()).isEqualTo("console_user");
        assertThat(created.get("actor").get("subject").asString()).isEqualTo("owner");
        assertThat(created.get("resource_type").asString()).isEqualTo("api_key");
        assertThat(created.get("project_id").asString()).isEqualTo(fixture.get("project"));
        assertThat(created.toString())
                .doesNotContain(fixture.get("key_plaintext").substring(8));

        JsonNode secret = awaitOne(() -> search(tenant, "action=secret.resolved&actor=runtime"));
        assertThat(secret.get("actor").get("type").asString()).isEqualTo("service");
        assertThat(secret.get("resource_id").asString()).isEqualTo(fixture.get("secret"));
        assertThat(secret.toString()).doesNotContain("fixture-secret-value");

        // 按操作者：owner 做过的全部管理操作
        assertThat(search(tenant, "actor=owner&limit=100"))
                .extracting(node -> node.get("action").asString())
                .contains("project.created", "api_key.created", "budget.created", "price_book.created");
    }

    @Test
    void runtimeAuditsAreSearchableBySessionResourceAndAction() {
        TenantFixture fixture = TenantFixture.create(client());
        String tenant = fixture.get("tenant");
        String session = Ids.newId(IdPrefix.SESSION);
        String occurredAt =
                Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60).toString();
        List<String> ids = new ArrayList<>();
        for (String name : OutboxSamples.AUDIT) {
            ids.add(publish(
                    redis,
                    "audit",
                    sample(
                            "audit",
                            name,
                            fields(
                                    "tenant_id",
                                    tenant,
                                    "project_id",
                                    fixture.get("project"),
                                    "session_id",
                                    session,
                                    "occurred_at",
                                    occurredAt))));
        }
        await("五条运行时审计入库", () -> ids.stream().allMatch(id -> count("audit_logs", id) == 1));

        assertThat(search(tenant, "session_id=" + session + "&limit=100"))
                .extracting(node -> node.get("action").asString())
                .containsExactlyInAnyOrder(
                        "turn.finished",
                        "tool.executed",
                        "sandbox.command",
                        "permission.decided",
                        "pending_action.concluded");
        // 权限判定：资源是工具调用，平铺的字段并入 details
        JsonNode decided = search(tenant, "action=permission.decided&session_id=" + session)
                .getFirst();
        assertThat(decided.get("resource_type").asString()).isEqualTo("tool_call");
        assertThat(decided.get("resource_id").asString()).isEqualTo("call_sample");
        assertThat(decided.get("details").get("decision").asString()).isEqualTo("ask");
        assertThat(decided.get("details").get("tool_name").asString()).isEqualTo("bash");
        // 待处理动作的回应：操作者是回应它的 API Key
        JsonNode concluded =
                search(tenant, "actor=key_sample&resource_type=pending_action").getFirst();
        assertThat(concluded.get("action").asString()).isEqualTo("pending_action.concluded");
        assertThat(concluded.get("details").get("outcome").asString()).isEqualTo("approved");
        // 沙箱命令：按资源检索
        JsonNode command =
                search(tenant, "action=sandbox.command&resource_id=call_bash").getFirst();
        assertThat(command.get("details").get("command").asString()).isEqualTo("python3 report.py");
        // 时间范围：发生时间之前的窗口里没有
        assertThat(search(tenant, "session_id=" + session + "&to=" + occurredAt))
                .isEmpty();
        // 别的租户看不到
        TenantFixture other = TenantFixture.create(client());
        assertThat(search(other.get("tenant"), "session_id=" + session)).isEmpty();
        // 重投不产生重复记录
        publish(
                redis,
                "audit",
                ids.getFirst(),
                sample(
                        "audit",
                        OutboxSamples.AUDIT.getFirst(),
                        fields(
                                "tenant_id",
                                tenant,
                                "project_id",
                                fixture.get("project"),
                                "session_id",
                                session,
                                "occurred_at",
                                occurredAt)));
        String marker = publish(
                redis,
                "audit",
                sample(
                        "audit",
                        "turn_finished",
                        fields(
                                "tenant_id",
                                tenant,
                                "session_id",
                                Ids.newId(IdPrefix.SESSION),
                                "occurred_at",
                                occurredAt)));
        await("标记入库", () -> count("audit_logs", marker) == 1);
        assertThat(count("audit_logs", ids.getFirst())).isEqualTo(1);
    }

    @Test
    void theApplicationRoleCanOnlyInsertAndReadAuditLogs() {
        TenantFixture fixture = TenantFixture.create(client());
        String tenant = fixture.get("tenant");
        String id = publish(
                redis,
                "audit",
                sample(
                        "audit",
                        "turn_finished",
                        fields(
                                "tenant_id",
                                tenant,
                                "occurred_at",
                                Instant.now().minusSeconds(5).toString())));
        await("入库", () -> count("audit_logs", id) == 1);
        String partition = LedgerStreams.db()
                .sql("SELECT tableoid::regclass::text FROM cp.audit_logs WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        JdbcClient app = JdbcClient.create(TestDatabase.appDataSource());

        assertThat(app.sql("SELECT count(*) FROM cp.audit_logs WHERE id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        for (String table : List.of("cp.audit_logs", partition, "cp.usage_records")) {
            assertThatThrownBy(() -> app.sql("UPDATE " + table + " SET tenant_id = 'x' WHERE id = :id")
                            .param("id", id)
                            .update())
                    .as("UPDATE " + table)
                    .rootCause()
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> app.sql("DELETE FROM " + table + " WHERE id = :id")
                            .param("id", id)
                            .update())
                    .as("DELETE " + table)
                    .rootCause()
                    .hasMessageContaining("permission denied");
        }
        assertThatThrownBy(() -> app.sql("TRUNCATE cp.audit_logs").update())
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThat(count("audit_logs", id)).isEqualTo(1);
    }

    private List<JsonNode> search(String tenant, String query) {
        Reply reply =
                client().admin("GET", "/admin/v1/tenants/" + tenant + "/audit-logs?" + query, token("owner"), null);
        assertThat(reply.status()).as(reply.raw()).isEqualTo(200);
        List<JsonNode> data = new ArrayList<>();
        reply.json().get("data").forEach(data::add);
        return data;
    }

    private static JsonNode awaitOne(Supplier<List<JsonNode>> query) {
        List<JsonNode> found = new ArrayList<>();
        await("审计可以检索", () -> {
            found.clear();
            found.addAll(query.get());
            return !found.isEmpty();
        });
        return found.getFirst();
    }
}
