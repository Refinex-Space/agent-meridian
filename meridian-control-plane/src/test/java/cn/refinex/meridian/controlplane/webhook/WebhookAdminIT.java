package cn.refinex.meridian.controlplane.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient;
import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import cn.refinex.meridian.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Webhook 的管理与内部接口（T17a，ADR-0049）：签名密钥只在创建响应中出现、库中只有密文，内部接口给出明文；地址按 Webhook 的地址策略
 * 校验（默认不放行内网）；修改与删除写审计并发布 {@code config.invalidate}；项目之间隔离。
 */
class WebhookAdminIT extends ControlPlaneTestBase {

    /** 测试配置放行回环（{@code application-test.yml}），公网地址的解析不依赖外部 DNS：用 IP 字面量。 */
    private static final String RECEIVER = "http://127.0.0.1:18999/hooks";

    @Autowired
    RedisMessaging redis;

    private final JdbcClient db = JdbcClient.create(TestDatabase.migratorDataSource());

    @Test
    void signingSecretIsReturnedOnceAndOnlyCiphertextIsStored() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String base = base(fixture);

        Reply created = client.admin(
                "POST",
                base,
                token("developer"),
                "{\"url\":\"" + RECEIVER + "\",\"events\":[\"session.status_terminated\",\"session.status_idle\"],"
                        + "\"description\":\"CRM\"}",
                Map.of("Idempotency-Key", "webhook-create-1"));
        String id = created.text("id");
        String secret = created.text("signing_secret");
        Reply replay = client.admin(
                "POST",
                base,
                token("developer"),
                "{\"url\":\"" + RECEIVER + "\",\"events\":[\"session.status_terminated\",\"session.status_idle\"],"
                        + "\"description\":\"CRM\"}",
                Map.of("Idempotency-Key", "webhook-create-1"));
        Reply got = client.admin("GET", base + "/" + id, token("viewer"), null);
        Reply listed = client.admin("GET", base, token("viewer"), null);

        assertThat(created.status()).isEqualTo(201);
        assertThat(id).startsWith("whk_");
        assertThat(secret).startsWith("whsec_").hasSizeGreaterThan(40);
        // 事件按固定顺序保存
        assertThat(created.json().get("events").toString())
                .isEqualTo("[\"session.status_idle\",\"session.status_terminated\"]");
        assertThat(created.json().get("enabled").asBoolean()).isTrue();
        assertThat(replay.text("signing_secret")).isEqualTo(secret);
        assertThat(got.status()).isEqualTo(200);
        assertThat(got.json().has("signing_secret")).isFalse();
        // 夹具在同一项目另建了一个
        assertThat(listed.json().get("data")).hasSize(2);
        assertThat(listed.raw()).doesNotContain(secret);

        byte[] ciphertext = db.sql("SELECT secret_ciphertext FROM cp.webhooks WHERE id = ?")
                .param(id)
                .query(byte[].class)
                .single();
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain(secret);
        assertThat(db.sql("SELECT payload::text FROM cp.outbox WHERE payload->>'tenant_id' = ?")
                        .param(fixture.get("tenant"))
                        .query(String.class)
                        .list())
                .anySatisfy(audit -> assertThat(audit).contains("\"webhook.created\""))
                .allSatisfy(audit -> assertThat(audit).doesNotContain(secret));

        Reply internal = client.internal(
                "GET", "/internal/v1/projects/" + fixture.get("project") + "/webhooks", serviceToken(), null);
        assertThat(internal.status()).isEqualTo(200);
        assertThat(internal.header("Cache-Control")).isEqualTo("no-store");
        JsonNode subscription = null;
        for (JsonNode node : internal.json().get("data")) {
            if (node.get("id").asString().equals(id)) {
                subscription = node;
            }
        }
        assertThat(subscription).isNotNull();
        assertThat(subscription.get("id").asString()).isEqualTo(id);
        assertThat(subscription.get("signing_secret").asString()).isEqualTo(secret);
        assertThat(subscription.get("tenant_id").asString()).isEqualTo(fixture.get("tenant"));
        // 另一个项目看不到它
        assertThat(client.internal(
                                "GET",
                                "/internal/v1/projects/" + fixture.get("other_project") + "/webhooks",
                                serviceToken(),
                                null)
                        .raw())
                .doesNotContain(id);
        assertThat(client.admin("GET", base(fixture, "other_project") + "/" + id, token("owner"), null)
                        .status())
                .isEqualTo(404);
        assertThat(client.internal("GET", "/internal/v1/projects/prj_missing/webhooks", serviceToken(), null)
                        .status())
                .isEqualTo(404);
    }

    @Test
    void urlsAreCheckedAgainstTheWebhookAddressPolicy() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String base = base(fixture);

        for (String url : List.of(
                "http://10.0.0.8/hooks",
                "http://169.254.169.254/latest",
                "ftp://example.com/hooks",
                "http://user:pass@127.0.0.1/hooks")) {
            Reply rejected = client.admin(
                    "POST", base, token("developer"), "{\"url\":\"" + url + "\",\"events\":[\"session.error\"]}");
            assertThat(rejected.status()).as(url).isEqualTo(400);
            assertThat(rejected.text("code")).as(url).isEqualTo("request.validation_failed");
        }
    }

    @Test
    void updatesAndDeletesAreAuditedAndBroadcast() throws Exception {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String tenantId = fixture.get("tenant");
        String base = base(fixture);
        String id = client.admin(
                        "POST", base, token("owner"), "{\"url\":\"" + RECEIVER + "\",\"events\":[\"session.error\"]}")
                .text("id");
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        try (var subscription = redis.subscribe(ConfigInvalidations.CHANNEL, messages::add)) {
            Thread.sleep(200); // 订阅在监听容器中异步生效
            Reply updated = client.admin(
                    "PUT",
                    base + "/" + id,
                    token("developer"),
                    "{\"url\":\"" + RECEIVER
                            + "/v2\",\"events\":[\"session.status_requires_action\"],\"enabled\":false}");
            assertThat(updated.status()).isEqualTo(200);
            assertThat(updated.text("url")).isEqualTo(RECEIVER + "/v2");
            assertThat(updated.json().get("enabled").asBoolean()).isFalse();
            assertThat(messages.poll(1, TimeUnit.SECONDS))
                    .isEqualTo("{\"type\":\"webhook\",\"tenant_id\":\"" + tenantId + "\",\"id\":\"" + id + "\"}");

            assertThat(client.admin("DELETE", base + "/" + id, token("developer"), null)
                            .status())
                    .isEqualTo(204);
            assertThat(messages.poll(1, TimeUnit.SECONDS)).contains("\"type\":\"webhook\"");
        }
        assertThat(client.admin("GET", base + "/" + id, token("owner"), null).status())
                .isEqualTo(404);
        assertThat(db.sql("""
                        SELECT payload->>'action' FROM cp.outbox
                         WHERE payload->>'tenant_id' = ? AND payload->>'resource_id' = ? ORDER BY id
                        """).params(tenantId, id).query(String.class).list())
                .containsExactly("webhook.created", "webhook.updated", "webhook.deleted");
    }

    private static String base(TenantFixture fixture) {
        return base(fixture, "project");
    }

    private static String base(TenantFixture fixture, String project) {
        return "/admin/v1/tenants/" + fixture.get("tenant") + "/projects/" + fixture.get(project) + "/webhooks";
    }
}
