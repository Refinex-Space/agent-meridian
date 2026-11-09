package cn.refinex.meridian.controlplane.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient;
import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import cn.refinex.meridian.testsupport.TestDatabase;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 管理操作的审计 outbox（T14a-A06）、租户配额的下发（T14a-A07 的 control-plane 部分）与写操作的幂等（架构设计「约定」）。
 */
class AdminAuditAndQuotaIT extends ControlPlaneTestBase {

    @Autowired
    RedisMessaging redis;

    @Test
    void adminOperationsWriteAuditOutboxRecordsWithoutTheKeyPlaintext() {
        TenantFixture fixture = TenantFixture.create(client());
        String tenantId = fixture.get("tenant");

        List<Map<String, Object>> records = JdbcClient.create(TestDatabase.migratorDataSource())
                .sql("""
                        SELECT topic, payload::text AS payload, payload->>'action' AS action
                          FROM cp.outbox WHERE payload->>'tenant_id' = :tenant ORDER BY id
                        """)
                .param("tenant", tenantId)
                .query()
                .listOfRows();

        assertThat(records).allSatisfy(record -> assertThat(record.get("topic")).isEqualTo("audit"));
        assertThat(records)
                .extracting(record -> record.get("action"))
                .contains(
                        "tenant.created", "member.added", "role_binding.created", "project.created", "api_key.created");
        String plaintext = fixture.get("key_plaintext");
        assertThat(records)
                .noneMatch(record -> String.valueOf(record.get("payload")).contains(plaintext.substring(8)));
        String created = records.stream()
                .filter(record -> "api_key.created".equals(record.get("action")))
                .map(record -> String.valueOf(record.get("payload")))
                .findFirst()
                .orElseThrow();
        assertThat(created)
                .contains("\"display_prefix\": \"" + plaintext.substring(0, 16) + "\"")
                .contains("\"subject\": \"owner\"")
                .contains("\"type\": \"console_user\"");
    }

    @Test
    void quotaChangesAreServedInternallyAndBroadcast() throws Exception {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String tenantId = fixture.get("tenant");
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        assertThat(client.internal("GET", "/internal/v1/tenants/" + tenantId + "/quotas", SERVICE_CREDENTIAL, null)
                        .json()
                        .get("max_concurrent_turns")
                        .asInt())
                .isEqualTo(20);
        try (var subscription = redis.subscribe(ConfigInvalidations.CHANNEL, messages::add)) {
            Thread.sleep(200); // 订阅在监听容器中异步生效
            Reply updated = client.admin(
                    "PUT",
                    "/admin/v1/tenants/" + tenantId + "/quotas",
                    token(PLATFORM),
                    "{\"max_concurrent_turns\":7,\"event_retention_days\":90}");
            assertThat(updated.json().get("max_concurrent_turns").asInt()).isEqualTo(7);
            assertThat(messages.poll(1, TimeUnit.SECONDS))
                    .isEqualTo("{\"type\":\"tenant_quota\",\"tenant_id\":\"" + tenantId + "\"}");
            // 只改保留天数不广播
            client.admin(
                    "PUT",
                    "/admin/v1/tenants/" + tenantId + "/quotas",
                    token(PLATFORM),
                    "{\"max_concurrent_turns\":7,\"event_retention_days\":60}");
            assertThat(messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
        assertThat(client.internal("GET", "/internal/v1/tenants/" + tenantId + "/quotas", SERVICE_CREDENTIAL, null)
                        .json()
                        .get("max_concurrent_turns")
                        .asInt())
                .isEqualTo(7);
        assertThat(client.internal("GET", "/internal/v1/tenants/ten_missing/quotas", SERVICE_CREDENTIAL, null)
                        .status())
                .isEqualTo(404);
    }

    @Test
    void writesWithTheSameIdempotencyKeyReplayTheFirstResult() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String path =
                "/admin/v1/tenants/" + fixture.get("tenant") + "/projects/" + fixture.get("project") + "/api-keys";
        Map<String, String> key = Map.of("Idempotency-Key", "create-key-1");
        String body = "{\"name\":\"幂等\",\"scopes\":[\"sessions:read\"]}";

        Reply first = client.admin("POST", path, token("owner"), body, key);
        Reply replay = client.admin("POST", path, token("owner"), body, key);
        Reply conflict =
                client.admin("POST", path, token("owner"), "{\"name\":\"另一个\",\"scopes\":[\"sessions:read\"]}", key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.text("id")).isEqualTo(first.text("id"));
        assertThat(replay.text("key")).isEqualTo(first.text("key"));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.text("code")).isEqualTo("idempotency.key_reused");
        // 同一个键，另一个用户互不影响
        assertThat(client.admin("POST", path, token("admin"), body, key).header("Idempotent-Replayed"))
                .isNull();
    }

    @Test
    void theLastOwnerCannotBeRemoved() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String base = "/admin/v1/tenants/" + fixture.get("tenant");

        assertThat(client.admin(
                                "DELETE",
                                base + "/role-bindings/" + fixture.get("owner2_binding"),
                                token("owner"),
                                null)
                        .status())
                .isEqualTo(204);
        Reply bindings = client.admin("GET", base + "/role-bindings?limit=100", token("owner"), null);
        String ownerBinding = bindings.json()
                .get("data")
                .valueStream()
                .filter(b -> b.get("role").asString().equals("tenant_owner"))
                .map(b -> b.get("id").asString())
                .findFirst()
                .orElseThrow();
        Reply last = client.admin("DELETE", base + "/role-bindings/" + ownerBinding, token("owner"), null);

        assertThat(last.status()).isEqualTo(409);
        assertThat(last.text("code")).isEqualTo("member.last_owner");
    }
}
