package cn.refinex.meridian.controlplane.secret;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.testsupport.TestDatabase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 含敏感内容的管理写操作的幂等记录（R2 审查 K-02、K-05）：API Key、Webhook 签名密钥的明文不进 {@code cp.idempotency_keys}，重放仍然
 * 返回同一个密钥；凭据值以带密钥摘要进入指纹，同一幂等键换了值是 409 而不是静默返回第一次的结果。
 */
class SensitiveIdempotencyIT extends ControlPlaneTestBase {

    @Test
    void oneTimeSecretsAreStoredEncryptedAndReplayedIntact() {
        TenantFixture fixture = TenantFixture.create(client());
        String tenant = fixture.get("tenant");
        String project = "/admin/v1/tenants/" + tenant + "/projects/" + fixture.get("project");
        String owner = token("owner");

        String keyRequest = "{\"name\":\"幂等 Key\",\"scopes\":[\"sessions:read\"]}";
        Map<String, String> keyHeaders = Map.of("Idempotency-Key", "key-" + UUID.randomUUID());
        Reply created = client().admin("POST", project + "/api-keys", owner, keyRequest, keyHeaders);
        Reply replayed = client().admin("POST", project + "/api-keys", owner, keyRequest, keyHeaders);
        assertThat(created.status()).isEqualTo(201);
        assertThat(replayed.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replayed.text("key")).isEqualTo(created.text("key"));

        String webhookRequest =
                "{\"url\":\"http://127.0.0.1:18999/meridian-hook\",\"events\":[\"session.status_idle\"]}";
        Map<String, String> webhookHeaders = Map.of("Idempotency-Key", "webhook-" + UUID.randomUUID());
        Reply webhook = client().admin("POST", project + "/webhooks", owner, webhookRequest, webhookHeaders);
        assertThat(webhook.status()).as(webhook.raw()).isEqualTo(201);
        Reply rotated = client().admin(
                        "POST",
                        project + "/webhooks/" + webhook.text("id") + ":rotate-secret",
                        owner,
                        null,
                        Map.of("Idempotency-Key", "rotate-" + UUID.randomUUID()));
        assertThat(rotated.status()).isEqualTo(200);

        List<String> stored = storedResponses(tenant);
        assertThat(stored)
                .hasSizeGreaterThanOrEqualTo(3)
                .allSatisfy(body -> assertThat(body).startsWith("enc:v1:"));
        assertThat(String.join("\n", stored))
                .doesNotContain(created.text("key"))
                .doesNotContain(webhook.text("signing_secret"))
                .doesNotContain(rotated.text("signing_secret"));
    }

    @Test
    void reusingAnIdempotencyKeyWithAnotherSecretValueIsAConflict() {
        TenantFixture fixture = TenantFixture.create(client());
        String base = "/admin/v1/tenants/" + fixture.get("tenant");
        String owner = token("owner");
        String secret = fixture.get("spare_secret");
        Map<String, String> headers = Map.of("Idempotency-Key", "rotate-" + UUID.randomUUID());

        Reply first =
                client().admin("POST", base + "/secrets/" + secret + ":rotate", owner, "{\"value\":\"v-1\"}", headers);
        Reply same =
                client().admin("POST", base + "/secrets/" + secret + ":rotate", owner, "{\"value\":\"v-1\"}", headers);
        Reply other =
                client().admin("POST", base + "/secrets/" + secret + ":rotate", owner, "{\"value\":\"v-2\"}", headers);

        assertThat(first.status()).isEqualTo(200);
        assertThat(same.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(same.json().get("version").asInt())
                .isEqualTo(first.json().get("version").asInt());
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.text("code")).isEqualTo("idempotency.key_reused");
        assertThat(String.join("\n", storedResponses(fixture.get("tenant")))).doesNotContain("v-1");
    }

    private static List<String> storedResponses(String tenantId) {
        return JdbcClient.create(TestDatabase.migratorDataSource())
                .sql("SELECT response_body FROM cp.idempotency_keys WHERE tenant_id = ? AND response_body IS NOT NULL")
                .param(tenantId)
                .query(String.class)
                .list();
    }
}
