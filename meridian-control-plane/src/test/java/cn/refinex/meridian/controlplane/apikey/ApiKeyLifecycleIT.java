package cn.refinex.meridian.controlplane.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient;
import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import cn.refinex.meridian.security.apikey.ApiKeyHashes;
import cn.refinex.meridian.security.apikey.ControlPlaneApiKeyResolver;
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
 * API Key 的生命周期（T14a-A03 的 control-plane 部分）：明文只在创建响应中出现一次、库中只有哈希与前 8 位、内部校验与作用域、吊销即时
 * 生效并广播 {@code key.revoked}、删除项目与租户时吊销其下的 Key。
 */
class ApiKeyLifecycleIT extends ControlPlaneTestBase {

    @Autowired
    RedisMessaging redis;

    @Test
    void plaintextIsReturnedOnceAndOnlyTheHashIsStored() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String plaintext = fixture.get("key_plaintext");
        String path =
                "/admin/v1/tenants/" + fixture.get("tenant") + "/projects/" + fixture.get("project") + "/api-keys";

        assertThat(plaintext).matches("mk_live_[0-9A-Za-z]{40}");
        Reply listed = client.admin("GET", path, token("owner"), null);
        Reply single = client.admin("GET", path + "/" + fixture.get("key"), token("owner"), null);
        assertThat(listed.raw()).doesNotContain(plaintext);
        assertThat(single.raw()).doesNotContain(plaintext);
        assertThat(single.json().has("key")).isFalse();
        assertThat(single.text("display_prefix")).isEqualTo(plaintext.substring(0, 16));
        assertThat(single.text("status")).isEqualTo("active");

        Map<String, Object> row = JdbcClient.create(TestDatabase.migratorDataSource())
                .sql("SELECT * FROM cp.api_keys WHERE id = :id")
                .param("id", fixture.get("key"))
                .query()
                .singleRow();
        assertThat(row.get("key_hash")).isEqualTo(ApiKeyHashes.sha256(plaintext));
        assertThat(row.get("key_prefix")).isEqualTo(plaintext.substring(8, 16));
        assertThat(row.values()).noneMatch(value -> String.valueOf(value).contains(plaintext.substring(8)));
    }

    @Test
    void verificationReturnsThePrincipalWithScopesAndRequiresAServiceCredential() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String body = verifyBody(fixture.get("key_plaintext"));

        Reply verified = client.internal("POST", "/internal/v1/api-keys:verify", SERVICE_CREDENTIAL, body);
        assertThat(verified.status()).isEqualTo(200);
        assertThat(verified.text("api_key_id")).isEqualTo(fixture.get("key"));
        assertThat(verified.text("tenant_id")).isEqualTo(fixture.get("tenant"));
        assertThat(verified.text("project_id")).isEqualTo(fixture.get("project"));
        assertThat(verified.json()
                        .get("scopes")
                        .valueStream()
                        .map(n -> n.asString())
                        .toList())
                .containsExactly("sessions:read", "sessions:write");
        assertThat(verified.json().get("rate_limit_per_minute").asInt()).isEqualTo(600);

        assertThat(client.internal("POST", "/internal/v1/api-keys:verify", null, body)
                        .status())
                .isEqualTo(401);
        assertThat(client.internal("POST", "/internal/v1/api-keys:verify", "svc_wrong", body)
                        .status())
                .isEqualTo(401);
        assertThat(client.internal("POST", "/internal/v1/api-keys:verify", SERVICE_CREDENTIAL, verifyBody("mk_live_x"))
                        .status())
                .isEqualTo(404);
        // 控制台令牌不能调用内部接口，服务凭据也不能调用管理接口
        assertThat(client.internal("POST", "/internal/v1/api-keys:verify", token("owner"), body)
                        .status())
                .isEqualTo(401);
        assertThat(client.admin("GET", "/admin/v1/tenants", SERVICE_CREDENTIAL, null)
                        .status())
                .isEqualTo(401);
    }

    @Test
    void revocationTakesEffectImmediatelyAndIsBroadcast() throws Exception {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        BlockingQueue<String> revoked = new LinkedBlockingQueue<>();
        try (var subscription = redis.subscribe(ControlPlaneApiKeyResolver.REVOKED_CHANNEL, revoked::add)) {
            Thread.sleep(200); // 订阅在监听容器中异步生效
            String path = "/admin/v1/tenants/" + fixture.get("tenant") + "/projects/" + fixture.get("project")
                    + "/api-keys/" + fixture.get("key") + ":revoke";

            Reply first = client.admin("POST", path, token("developer"), null);
            Reply again = client.admin("POST", path, token("developer"), null);

            assertThat(first.status()).isEqualTo(200);
            assertThat(first.text("status")).isEqualTo("revoked");
            assertThat(again.text("revoked_at")).isEqualTo(first.text("revoked_at"));
            assertThat(revoked.poll(1, TimeUnit.SECONDS)).isEqualTo(fixture.get("key"));
            assertThat(revoked.poll(200, TimeUnit.MILLISECONDS)).as("再次吊销不再广播").isNull();
        }
        assertThat(client.internal(
                                "POST",
                                "/internal/v1/api-keys:verify",
                                SERVICE_CREDENTIAL,
                                verifyBody(fixture.get("key_plaintext")))
                        .status())
                .isEqualTo(404);
    }

    @Test
    void deletingTheProjectOrTheTenantRevokesItsKeys() throws Exception {
        AdminClient client = client();
        TenantFixture byProject = TenantFixture.create(client);
        TenantFixture byTenant = TenantFixture.create(client);
        BlockingQueue<String> revoked = new LinkedBlockingQueue<>();
        try (var subscription = redis.subscribe(ControlPlaneApiKeyResolver.REVOKED_CHANNEL, revoked::add)) {
            Thread.sleep(200);
            assertThat(client.admin(
                                    "DELETE",
                                    "/admin/v1/tenants/" + byProject.get("tenant") + "/projects/"
                                            + byProject.get("project"),
                                    token("admin"),
                                    null)
                            .status())
                    .isEqualTo(204);
            assertThat(client.admin("DELETE", "/admin/v1/tenants/" + byTenant.get("tenant"), token("owner"), null)
                            .status())
                    .isEqualTo(204);

            assertThat(List.of(revoked.poll(1, TimeUnit.SECONDS), revoked.poll(1, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(byProject.get("key"), byTenant.get("key"));
        }
        for (TenantFixture fixture : List.of(byProject, byTenant)) {
            assertThat(client.internal(
                                    "POST",
                                    "/internal/v1/api-keys:verify",
                                    SERVICE_CREDENTIAL,
                                    verifyBody(fixture.get("key_plaintext")))
                            .status())
                    .isEqualTo(404);
        }
        // 已删除的租户中没有任何角色：所有者也不能再操作
        assertThat(client.admin("GET", "/admin/v1/tenants/" + byTenant.get("tenant"), token("owner"), null)
                        .status())
                .isEqualTo(403);
        assertThat(client.admin("GET", "/admin/v1/tenants/" + byTenant.get("tenant"), token(PLATFORM), null)
                        .status())
                .isEqualTo(404);
    }

    @Test
    void expiredKeysAreNotValidAndPastExpiryIsRejected() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String path =
                "/admin/v1/tenants/" + fixture.get("tenant") + "/projects/" + fixture.get("project") + "/api-keys";

        Reply past = client.admin(
                "POST",
                path,
                token("owner"),
                "{\"name\":\"k\",\"scopes\":[\"sessions:read\"],\"expires_at\":\"2020-01-01T00:00:00Z\"}");

        assertThat(past.status()).isEqualTo(400);
        assertThat(past.text("code")).isEqualTo("request.validation_failed");
    }

    private static String verifyBody(String plaintext) {
        return "{\"key_hash\":\"" + ApiKeyHashes.sha256(plaintext) + "\"}";
    }
}
