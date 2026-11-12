package cn.refinex.meridian.controlplane.secret;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.secret.app.KeyEncryptionService;
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
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 凭据库（T15a-A01 的 control-plane 部分，ADR-0045）：明文无法经任何管理接口读出，库中只有密文，不同租户的数据密钥不同，每次解析都有
 * 审计，租户与项目校验，轮换与删除的通知、引用冲突；日志（DEBUG）、outbox 与幂等记录中都没有明文。
 */
@ExtendWith(OutputCaptureExtension.class)
class SecretStoreIT extends ControlPlaneTestBase {

    @Autowired
    RedisMessaging redis;

    @Autowired
    KeyEncryptionService encryption;

    private final JdbcClient db = JdbcClient.create(TestDatabase.migratorDataSource());

    @Test
    void plaintextIsNeverReadableThroughTheAdminApiAndOnlyCiphertextIsStored(CapturedOutput output) {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String base = "/admin/v1/tenants/" + fixture.get("tenant") + "/secrets";
        String value = "sk-a01-" + UUID.randomUUID();

        Reply created = client.admin(
                "POST",
                base,
                token("admin"),
                "{\"name\":\"DashScope\",\"value\":\"" + value + "\"}",
                Map.of("Idempotency-Key", "secret-create-1"));
        String secretId = created.text("id");
        Reply replay = client.admin(
                "POST",
                base,
                token("admin"),
                "{\"name\":\"DashScope\",\"value\":\"" + value + "\"}",
                Map.of("Idempotency-Key", "secret-create-1"));
        Reply got = client.admin("GET", base + "/" + secretId, token("owner"), null);
        Reply listed = client.admin("GET", base + "?limit=100", token("owner"), null);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().has("value")).isFalse();
        assertThat(created.json().get("version").asInt()).isEqualTo(1);
        assertThat(created.json().get("project_id").isNull()).isTrue();
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.text("id")).isEqualTo(secretId);
        for (Reply reply : List.of(created, replay, got, listed)) {
            assertThat(reply.raw()).doesNotContain(value);
        }
        assertThat(got.json().get("references")).isEmpty();
        assertThat(got.json().get("last_used_at").isNull()).isTrue();

        byte[] ciphertext = db.sql("SELECT ciphertext FROM cp.secrets WHERE id = ?")
                .param(secretId)
                .query(byte[].class)
                .single();
        assertThat(ciphertext).hasSize(12 + value.length() + 16);
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain(value);
        assertThat(rowsAsText("SELECT payload::text FROM cp.outbox WHERE payload->>'tenant_id' = ?", fixture))
                .noneMatch(payload -> payload.contains(value));
        assertThat(db.sql("SELECT count(*) FROM cp.idempotency_keys WHERE response_body LIKE ?")
                        .param("%" + value + "%")
                        .query(Long.class)
                        .single())
                .isZero();

        Reply resolved = resolve(fixture.get("tenant"), fixture.get("project"), secretId);
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.text("value")).isEqualTo(value);
        assertThat(resolved.header("Cache-Control")).isEqualTo("no-store");
        assertThat(output.getAll()).doesNotContain(value);
    }

    @Test
    void eachTenantHasItsOwnDataKey() {
        AdminClient client = client();
        TenantFixture a = TenantFixture.create(client);
        TenantFixture b = TenantFixture.create(client);

        List<Map<String, Object>> keys =
                db.sql("""
                        SELECT tenant_id, key_encryption, kek_id, wrapped_key FROM cp.secret_data_keys
                         WHERE tenant_id IN (?, ?) ORDER BY tenant_id
                        """).params(a.get("tenant"), b.get("tenant")).query().listOfRows();

        assertThat(keys).hasSize(2);
        assertThat(keys).allSatisfy(row -> {
            assertThat(row.get("key_encryption")).isEqualTo("local");
            assertThat(row.get("kek_id")).isEqualTo(encryption.keyId());
        });
        byte[] keyA = encryption.unwrap(a.get("tenant"), wrapped(keys, a));
        byte[] keyB = encryption.unwrap(b.get("tenant"), wrapped(keys, b));
        assertThat(keyA).hasSize(32).isNotEqualTo(keyB);
        // 另一个租户的密文不能用本租户的数据密钥解开：解析时附加认证数据与密钥都不同
        assertThat(resolve(b.get("tenant"), null, a.get("secret")).status()).isEqualTo(404);
    }

    @Test
    void everyResolutionIsAuditedAndRecordsLastUse() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String tenantId = fixture.get("tenant");

        for (int i = 0; i < 3; i++) {
            assertThat(resolve(tenantId, fixture.get("project"), fixture.get("secret"))
                            .text("value"))
                    .isEqualTo("fixture-secret-value");
        }

        List<String> audits = rowsAsText(
                "SELECT payload::text FROM cp.outbox WHERE payload->>'tenant_id' = ? AND payload->>'action' = 'secret.resolved'",
                fixture);
        assertThat(audits)
                .hasSize(3)
                .allSatisfy(payload -> assertThat(payload)
                        .contains("\"resource_id\": \"" + fixture.get("secret") + "\"")
                        .contains("\"type\": \"service\"")
                        .contains("\"subject\": \"runtime\"")
                        .contains("\"project_id\": \"" + fixture.get("project") + "\"")
                        .doesNotContain("fixture-secret-value"));
        Reply got = client.admin(
                "GET", "/admin/v1/tenants/" + tenantId + "/secrets/" + fixture.get("secret"), token("owner"), null);
        assertThat(got.json().get("last_used_at").isNull()).isFalse();
        assertThat(got.json().get("references").get(0).get("type").asString()).isEqualTo("provider_account");
        assertThat(got.json().get("references").get(0).get("id").asString()).isEqualTo(fixture.get("provider"));
    }

    @Test
    void resolutionChecksTheTenantAndTheProject() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        TenantFixture other = TenantFixture.create(client);
        String tenantId = fixture.get("tenant");
        Reply projectSecret = client.admin(
                "POST",
                "/admin/v1/tenants/" + tenantId + "/secrets",
                token("owner"),
                "{\"name\":\"项目凭据\",\"project_id\":\"" + fixture.get("project") + "\",\"value\":\"project-value\"}");
        String scoped = projectSecret.text("id");

        // 租户级凭据：本租户的任何项目、没有项目上下文都可以；别的租户不行
        assertThat(resolve(tenantId, fixture.get("other_project"), fixture.get("secret"))
                        .status())
                .isEqualTo(200);
        assertThat(resolve(tenantId, null, fixture.get("secret")).status()).isEqualTo(200);
        Reply foreign = resolve(other.get("tenant"), other.get("project"), fixture.get("secret"));
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.text("code")).isEqualTo("secret.not_found");
        // 项目级凭据：只有本项目
        assertThat(resolve(tenantId, fixture.get("project"), scoped).text("value"))
                .isEqualTo("project-value");
        assertThat(resolve(tenantId, fixture.get("other_project"), scoped).status())
                .isEqualTo(404);
        assertThat(resolve(tenantId, null, scoped).status()).isEqualTo(404);
        // 没有服务 JWT 不能调用
        assertThat(client.internal(
                                "POST",
                                "/internal/v1/secrets:resolve",
                                null,
                                "{\"tenant_id\":\"" + tenantId + "\",\"secret_id\":\"" + scoped + "\"}")
                        .status())
                .isEqualTo(401);
        // 项目级凭据不能被租户级的供应商账号引用
        Reply account = client.admin(
                "POST",
                "/admin/v1/tenants/" + tenantId + "/provider-accounts",
                token("owner"),
                "{\"name\":\"引用项目凭据\",\"provider\":\"anthropic\",\"secret_id\":\"" + scoped + "\"}");
        assertThat(account.status()).isEqualTo(400);
        assertThat(account.json().get("errors").get(0).get("field").asString()).isEqualTo("secret_id");
    }

    @Test
    void rotationAndDeletionNotifyRuntimeAndReferencedSecretsCannotBeDeleted() throws Exception {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String tenantId = fixture.get("tenant");
        String base = "/admin/v1/tenants/" + tenantId + "/secrets/";
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        try (var subscription = redis.subscribe(ConfigInvalidations.CHANNEL, messages::add)) {
            Thread.sleep(200); // 订阅在监听容器中异步生效
            Reply rotated = client.admin(
                    "POST", base + fixture.get("secret") + ":rotate", token("admin"), "{\"value\":\"rotated-value\"}");
            assertThat(rotated.status()).isEqualTo(200);
            assertThat(rotated.json().get("version").asInt()).isEqualTo(2);
            assertThat(rotated.raw()).doesNotContain("rotated-value");
            assertThat(messages.poll(1, TimeUnit.SECONDS))
                    .isEqualTo("{\"type\":\"secret\",\"tenant_id\":\"" + tenantId + "\",\"id\":\""
                            + fixture.get("secret") + "\"}");
            Reply resolved = resolve(tenantId, null, fixture.get("secret"));
            assertThat(resolved.text("value")).isEqualTo("rotated-value");
            assertThat(resolved.json().get("version").asInt()).isEqualTo(2);

            Reply inUse = client.admin("DELETE", base + fixture.get("secret"), token("admin"), null);
            assertThat(inUse.status()).isEqualTo(409);
            assertThat(inUse.text("code")).isEqualTo("secret.in_use");
            assertThat(inUse.json().get("references").get(0).get("type").asString())
                    .isEqualTo("provider_account");
            assertThat(inUse.json().get("references").get(0).get("name").asString())
                    .isEqualTo("夹具账号");

            // 租户设置的联网搜索后端也是引用方
            Reply settings = client.admin(
                    "PUT",
                    "/admin/v1/tenants/" + tenantId + "/settings",
                    token("owner"),
                    "{\"system_prompt\":\"\",\"web_search\":{\"backend\":\"searxng\",\"base_url\":\"http://127.0.0.1:8888\","
                            + "\"secret_id\":\"" + fixture.get("spare_secret") + "\"}}");
            assertThat(settings.status()).as(settings.raw()).isEqualTo(200);
            Reply settingsRef = client.admin("DELETE", base + fixture.get("spare_secret"), token("admin"), null);
            assertThat(settingsRef.status()).isEqualTo(409);
            assertThat(settingsRef.json().get("references").get(0).get("type").asString())
                    .isEqualTo("tenant_settings");
            client.admin(
                    "PUT",
                    "/admin/v1/tenants/" + tenantId + "/settings",
                    token("owner"),
                    "{\"system_prompt\":\"\",\"web_search\":null}");

            messages.clear();
            assertThat(client.admin("DELETE", base + fixture.get("spare_secret"), token("admin"), null)
                            .status())
                    .isEqualTo(204);
            assertThat(messages.poll(1, TimeUnit.SECONDS)).contains("\"type\":\"secret\"");
            assertThat(resolve(tenantId, null, fixture.get("spare_secret")).status())
                    .isEqualTo(404);
            assertThat(client.admin("GET", base + fixture.get("spare_secret"), token("admin"), null)
                            .status())
                    .isEqualTo(404);
        }
    }

    @Test
    void namesAreUniqueWithinAScope() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String base = "/admin/v1/tenants/" + fixture.get("tenant") + "/secrets";

        Reply duplicate = client.admin("POST", base, token("owner"), "{\"name\":\"夹具凭据\",\"value\":\"another\"}");
        Reply inProject = client.admin(
                "POST",
                base,
                token("owner"),
                "{\"name\":\"夹具凭据\",\"project_id\":\"" + fixture.get("project") + "\",\"value\":\"another\"}");
        Reply missingProject = client.admin(
                "POST", base, token("owner"), "{\"name\":\"x\",\"project_id\":\"prj_missing\",\"value\":\"another\"}");

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.text("code")).isEqualTo("secret.name_taken");
        assertThat(inProject.status()).isEqualTo(201);
        assertThat(missingProject.status()).isEqualTo(404);
    }

    private Reply resolve(String tenantId, String projectId, String secretId) {
        String body = "{\"tenant_id\":\"" + tenantId + "\",\"project_id\":"
                + (projectId == null ? "null" : "\"" + projectId + "\"") + ",\"secret_id\":\"" + secretId + "\"}";
        return client().internal("POST", "/internal/v1/secrets:resolve", serviceToken(), body);
    }

    private List<String> rowsAsText(String sql, TenantFixture fixture) {
        return db.sql(sql).param(fixture.get("tenant")).query(String.class).list();
    }

    private static String wrapped(List<Map<String, Object>> keys, TenantFixture fixture) {
        return keys.stream()
                .filter(row -> row.get("tenant_id").equals(fixture.get("tenant")))
                .map(row -> (String) row.get("wrapped_key"))
                .findFirst()
                .orElseThrow();
    }
}
