package cn.refinex.meridian.controlplane.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient;
import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 模型目录（T15a-A04、A05 的 control-plane 部分，ADR-0045）：内部解析接口的内容、价格表版本化、变更通知、删除被引用资源的冲突、
 * 端点与引用的校验（D52）。
 */
class ModelCatalogIT extends ControlPlaneTestBase {

    @Autowired
    RedisMessaging redis;

    @Test
    void internalResolutionCarriesAccountAndCurrentPriceBookVersionWithoutPlaintext() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);

        Reply resolved = catalogModel(fixture.get("model"));

        assertThat(resolved.status()).isEqualTo(200);
        JsonNode body = resolved.json();
        assertThat(body.get("tenant_id").asString()).isEqualTo(fixture.get("tenant"));
        assertThat(body.get("kind").asString()).isEqualTo("chat");
        assertThat(body.get("upstream_model").asString()).isEqualTo("scripted");
        assertThat(body.get("price_book_id").asString()).isEqualTo(fixture.get("price_book"));
        assertThat(body.get("price_book_version").asInt()).isEqualTo(1);
        assertThat(body.get("provider_account").get("provider").asString()).isEqualTo("openai_compatible");
        assertThat(body.get("provider_account").get("base_url").asString()).isEqualTo("http://127.0.0.1:18090/v1");
        assertThat(body.get("provider_account").get("secret_id").asString()).isEqualTo(fixture.get("secret"));
        assertThat(resolved.raw()).doesNotContain("fixture-secret-value");
        Reply missing = catalogModel("mdl_missing");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.text("code")).isEqualTo("catalog.model_not_found");
        assertThat(client.internal("GET", "/internal/v1/models/" + fixture.get("model"), null, null)
                        .status())
                .isEqualTo(401);
    }

    @Test
    void newPriceBookVersionsTakeEffectAndOldVersionsStayUnchanged() throws Exception {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String tenantId = fixture.get("tenant");
        String book = "/admin/v1/tenants/" + tenantId + "/price-books/" + fixture.get("price_book");
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        try (var subscription = redis.subscribe(ConfigInvalidations.CHANNEL, messages::add)) {
            Thread.sleep(200); // 订阅在监听容器中异步生效
            Reply published = client.admin(
                    "POST",
                    book + "/versions",
                    token("admin"),
                    "{\"prices\":{\"input\":\"1.2\",\"output\":\"3\",\"cache_read\":\"0.24\",\"cache_write\":\"1.2\","
                            + "\"reasoning\":\"12.000001\"}}");
            assertThat(published.status()).isEqualTo(201);
            assertThat(published.json().get("version").asInt()).isEqualTo(2);
            assertThat(messages.poll(1, TimeUnit.SECONDS))
                    .isEqualTo("{\"type\":\"price_book\",\"tenant_id\":\"" + tenantId + "\",\"id\":\""
                            + fixture.get("price_book") + "\"}");
        }

        assertThat(catalogModel(fixture.get("model"))
                        .json()
                        .get("price_book_version")
                        .asInt())
                .isEqualTo(2);
        Reply current = client.admin("GET", book, token("viewer"), null);
        assertThat(current.json().get("current_version").get("version").asInt()).isEqualTo(2);
        assertThat(current.json()
                        .get("current_version")
                        .get("prices")
                        .get("reasoning")
                        .asString())
                .isEqualTo("12.000001");
        Reply versions = client.admin("GET", book + "/versions?limit=1", token("viewer"), null);
        assertThat(versions.json().get("data").get(0).get("version").asInt()).isEqualTo(2);
        assertThat(versions.json().get("has_more").asBoolean()).isTrue();
        Reply older = client.admin(
                "GET", book + "/versions?limit=1&after=" + versions.text("next_cursor"), token("viewer"), null);
        JsonNode v1 = older.json().get("data").get(0);
        assertThat(v1.get("version").asInt()).isEqualTo(1);
        assertThat(v1.get("prices").get("input").asString()).isEqualTo("0.8");
        assertThat(v1.get("prices").get("output").asString()).isEqualTo("2");
        assertThat(v1.get("prices").get("cache_read").asString()).isEqualTo("0.16");
    }

    @Test
    void changesAreBroadcastAndReferencedResourcesCannotBeDeleted() throws Exception {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String tenantId = fixture.get("tenant");
        String base = "/admin/v1/tenants/" + tenantId;
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        try (var subscription = redis.subscribe(ConfigInvalidations.CHANNEL, messages::add)) {
            Thread.sleep(200);
            Reply updated = client.admin(
                    "PUT",
                    base + "/provider-accounts/" + fixture.get("provider"),
                    token("admin"),
                    "{\"name\":\"改名账号\",\"base_url\":\"http://127.0.0.1:18091/v1\",\"secret_id\":null,"
                            + "\"rate_limit\":{\"rpm\":30,\"tpm\":null}}");
            assertThat(updated.status()).isEqualTo(200);
            assertThat(updated.json().get("secret_id").isNull()).isTrue();
            assertThat(updated.json().get("rate_limit").get("rpm").asInt()).isEqualTo(30);
            assertThat(messages.poll(1, TimeUnit.SECONDS))
                    .isEqualTo("{\"type\":\"provider_account\",\"tenant_id\":\"" + tenantId + "\",\"id\":\""
                            + fixture.get("provider") + "\"}");

            Reply modelUpdated = client.admin(
                    "PUT",
                    base + "/models/" + fixture.get("model"),
                    token("admin"),
                    "{\"name\":\"夹具模型\",\"upstream_model\":\"scripted-2\",\"context_window\":65536,"
                            + "\"max_output_tokens\":1024,\"capabilities\":{\"tool_calls\":true,\"vision\":true,"
                            + "\"reasoning\":false},\"fallback_model_id\":\"" + fixture.get("spare_model")
                            + "\",\"price_book_id\":null,\"base_url\":null,\"max_batch_size\":null,"
                            + "\"rate_limit\":{\"rpm\":null,\"tpm\":5000}}");
            assertThat(modelUpdated.status()).as(modelUpdated.raw()).isEqualTo(200);
            assertThat(messages.poll(1, TimeUnit.SECONDS)).contains("\"type\":\"model\"");
        }
        JsonNode resolved = catalogModel(fixture.get("model")).json();
        assertThat(resolved.get("upstream_model").asString()).isEqualTo("scripted-2");
        assertThat(resolved.get("fallback_model_id").asString()).isEqualTo(fixture.get("spare_model"));
        assertThat(resolved.get("price_book_version").isNull()).isTrue();
        assertThat(resolved.get("rate_limit").get("tpm").asInt()).isEqualTo(5000);
        assertThat(resolved.get("provider_account").get("rate_limit").get("rpm").asInt())
                .isEqualTo(30);

        Reply providerInUse =
                client.admin("DELETE", base + "/provider-accounts/" + fixture.get("provider"), token("admin"), null);
        assertThat(providerInUse.status()).isEqualTo(409);
        assertThat(providerInUse.text("code")).isEqualTo("catalog.in_use");
        assertThat(providerInUse.json().get("references"))
                .extracting(ref -> ref.get("id").asString())
                .containsExactlyInAnyOrder(fixture.get("model"), fixture.get("spare_model"));
        Reply fallbackInUse =
                client.admin("DELETE", base + "/models/" + fixture.get("spare_model"), token("admin"), null);
        assertThat(fallbackInUse.status()).isEqualTo(409);
        assertThat(fallbackInUse.json().get("references").get(0).get("id").asString())
                .isEqualTo(fixture.get("model"));

        assertThat(client.admin("DELETE", base + "/models/" + fixture.get("model"), token("admin"), null)
                        .status())
                .isEqualTo(204);
        assertThat(catalogModel(fixture.get("model")).status()).isEqualTo(404);
        assertThat(client.admin("DELETE", base + "/models/" + fixture.get("spare_model"), token("admin"), null)
                        .status())
                .isEqualTo(204);
        assertThat(client.admin("DELETE", base + "/provider-accounts/" + fixture.get("provider"), token("admin"), null)
                        .status())
                .isEqualTo(204);
    }

    @Test
    void endpointsAndReferencesAreValidated() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        TenantFixture other = TenantFixture.create(client);
        String base = "/admin/v1/tenants/" + fixture.get("tenant");

        assertInvalid(
                client.admin(
                        "POST",
                        base + "/provider-accounts",
                        token("owner"),
                        "{\"name\":\"元数据\",\"provider\":\"openai_compatible\",\"base_url\":\"http://169.254.169.254/v1\"}"),
                "base_url");
        assertInvalid(
                client.admin(
                        "POST",
                        base + "/provider-accounts",
                        token("owner"),
                        "{\"name\":\"文件\",\"provider\":\"openai_compatible\",\"base_url\":\"file:///etc/passwd\"}"),
                "base_url");
        assertInvalid(
                client.admin(
                        "POST",
                        base + "/provider-accounts",
                        token("owner"),
                        "{\"name\":\"缺端点\",\"provider\":\"openai_compatible\"}"),
                "base_url");
        assertInvalid(
                client.admin(
                        "POST",
                        base + "/provider-accounts",
                        token("owner"),
                        "{\"name\":\"别人的凭据\",\"provider\":\"gemini\",\"secret_id\":\"" + other.get("secret") + "\"}"),
                "secret_id");
        // 内网地址在默认的私有网段内放行（私有化推理服务）
        assertThat(client.admin(
                                "POST",
                                base + "/provider-accounts",
                                token("owner"),
                                "{\"name\":\"vLLM\",\"provider\":\"openai_compatible\",\"base_url\":\"http://10.20.0.5:8000/v1\"}")
                        .status())
                .isEqualTo(201);

        assertInvalid(
                client.admin(
                        "POST",
                        base + "/models",
                        token("owner"),
                        "{\"provider_account_id\":\"" + fixture.get("spare_provider")
                                + "\",\"name\":\"e\",\"kind\":\"embedding\","
                                + "\"upstream_model\":\"x\",\"context_window\":8192,\"max_output_tokens\":1}"),
                "kind");
        String embedding = client.admin(
                        "POST",
                        base + "/models",
                        token("owner"),
                        "{\"provider_account_id\":\"" + fixture.get("provider")
                                + "\",\"name\":\"向量\",\"kind\":\"embedding\","
                                + "\"upstream_model\":\"x\",\"context_window\":8192,\"max_output_tokens\":1,\"max_batch_size\":20}")
                .text("id");
        assertInvalid(
                client.admin(
                        "POST",
                        base + "/models",
                        token("owner"),
                        modelWith(fixture, "\"fallback_model_id\":\"" + embedding + "\"")),
                "fallback_model_id");
        assertInvalid(
                client.admin(
                        "POST",
                        base + "/models",
                        token("owner"),
                        modelWith(fixture, "\"fallback_model_id\":\"" + other.get("model") + "\"")),
                "fallback_model_id");
        assertInvalid(
                client.admin(
                        "POST",
                        base + "/models",
                        token("owner"),
                        modelWith(fixture, "\"price_book_id\":\"" + other.get("price_book") + "\"")),
                "price_book_id");
        assertInvalid(
                client.admin(
                        "POST",
                        base + "/models",
                        token("owner"),
                        modelWith(fixture, "\"base_url\":\"http://[fd00:ec2::254]/v1\"")),
                "base_url");
        Reply foreignAccount = client.admin(
                "POST", base + "/models", token("owner"), TenantFixture.model(other.get("provider"), "跨租户", null));
        assertThat(foreignAccount.status()).isEqualTo(404);
        assertThat(foreignAccount.text("code")).isEqualTo("catalog.provider_account_not_found");
        Reply duplicate = client.admin(
                "POST", base + "/models", token("owner"), TenantFixture.model(fixture.get("provider"), "夹具模型", null));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.text("code")).isEqualTo("catalog.name_taken");
        // 联网搜索后端的端点同样校验（D52）
        assertInvalid(
                client.admin(
                        "PUT",
                        base + "/settings",
                        token("owner"),
                        "{\"system_prompt\":\"\",\"web_search\":{\"backend\":\"searxng\",\"base_url\":\"http://100.100.100.200\","
                                + "\"secret_id\":null}}"),
                "web_search.base_url");
    }

    private Reply catalogModel(String modelId) {
        return client().internal("GET", "/internal/v1/models/" + modelId, serviceToken(), null);
    }

    private static String modelWith(TenantFixture fixture, String extra) {
        String json = TenantFixture.model(fixture.get("provider"), "校验模型", null);
        return json.substring(0, json.length() - 1) + "," + extra + "}";
    }

    private static void assertInvalid(Reply reply, String field) {
        assertThat(reply.status()).as(reply.raw()).isEqualTo(400);
        assertThat(reply.text("code")).isEqualTo("request.validation_failed");
        assertThat(reply.json().get("errors").get(0).get("field").asString()).isEqualTo(field);
    }
}
