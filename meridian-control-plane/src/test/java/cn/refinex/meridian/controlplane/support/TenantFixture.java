package cn.refinex.meridian.controlplane.support;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个经管理 API 建好的租户：所有者 {@code owner}、第二位所有者 {@code owner2}、管理员 {@code admin}，项目 {@code project} 上的开发者、
 * 观察者、审批人，另一个项目 {@code other_project}，可被移除的成员 {@code spare}（{@code project} 的观察者），{@code project} 下的
 * 一个 API Key，以及凭据与模型目录（T15a）。
 *
 * @param values 占位符到 ID 的映射：tenant、project、other_project、spare_member、spare_binding、owner2_member、owner2_binding、
 *     key、key_plaintext、secret、spare_secret、provider、spare_provider、price_book、model、spare_model
 */
public record TenantFixture(Map<String, String> values) {

    /**
     * 建一个新租户。
     *
     * @param client 客户端
     * @return 夹具
     */
    public static TenantFixture create(AdminClient client) {
        Map<String, String> v = new LinkedHashMap<>();
        Reply tenant = ok(client.admin(
                "POST",
                "/admin/v1/tenants",
                ControlPlaneTestBase.token(ControlPlaneTestBase.PLATFORM),
                "{\"name\":\"夹具租户\",\"owner\":{\"subject\":\"owner\",\"email\":\"owner@example.com\"}}"));
        String t = tenant.text("id");
        v.put("tenant", t);
        String owner = ControlPlaneTestBase.token("owner");
        String base = "/admin/v1/tenants/" + t;
        v.put(
                "project",
                ok(client.admin("POST", base + "/projects", owner, "{\"name\":\"P\"}"))
                        .text("id"));
        v.put(
                "other_project",
                ok(client.admin("POST", base + "/projects", owner, "{\"name\":\"Q\"}"))
                        .text("id"));
        Map<String, String> members = new LinkedHashMap<>();
        for (String subject : new String[] {"admin", "developer", "viewer", "approver", "owner2", "spare"}) {
            members.put(
                    subject,
                    ok(client.admin("POST", base + "/members", owner, "{\"subject\":\"" + subject + "\"}"))
                            .text("id"));
        }
        bind(client, base, owner, members.get("admin"), "tenant_admin", null);
        bind(client, base, owner, members.get("developer"), "project_developer", v.get("project"));
        bind(client, base, owner, members.get("viewer"), "project_viewer", v.get("project"));
        bind(client, base, owner, members.get("approver"), "approver", v.get("project"));
        v.put("owner2_member", members.get("owner2"));
        v.put("owner2_binding", bind(client, base, owner, members.get("owner2"), "tenant_owner", null));
        v.put("spare_member", members.get("spare"));
        v.put("spare_binding", bind(client, base, owner, members.get("spare"), "project_viewer", v.get("project")));
        Reply key = ok(client.admin(
                "POST",
                base + "/projects/" + v.get("project") + "/api-keys",
                owner,
                "{\"name\":\"夹具 Key\",\"scopes\":[\"sessions:read\",\"sessions:write\"]}"));
        v.put("key", key.text("id"));
        v.put("key_plaintext", key.text("key"));
        catalog(client, base, owner, v);
        return new TenantFixture(Map.copyOf(v));
    }

    /**
     * 取一个值。
     *
     * @param name 占位符名
     * @return ID
     */
    public String get(String name) {
        return values.get(name);
    }

    /**
     * 替换文本中的占位符。
     *
     * @param text 含 {@code {名称}} 的文本；可为空
     * @return 替换后的文本
     */
    public String fill(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * 凭据与模型目录（T15a）：被供应商账号引用的凭据 {@code secret}、没有引用方的 {@code spare_secret}；挂着模型的供应商账号
     * {@code provider} 与空账号 {@code spare_provider}；价格表 {@code price_book}；模型 {@code model} 与没有引用方的 {@code spare_model}。
     * 端点在回环地址上（测试配置放行回环），不做 DNS 解析。
     */
    private static void catalog(AdminClient client, String base, String owner, Map<String, String> v) {
        v.put(
                "secret",
                ok(client.admin(
                                "POST",
                                base + "/secrets",
                                owner,
                                "{\"name\":\"夹具凭据\",\"value\":\"fixture-secret-value\"}"))
                        .text("id"));
        v.put(
                "spare_secret",
                ok(client.admin(
                                "POST",
                                base + "/secrets",
                                owner,
                                "{\"name\":\"备用凭据\",\"value\":\"spare-secret-value\"}"))
                        .text("id"));
        v.put(
                "provider",
                ok(client.admin(
                                "POST",
                                base + "/provider-accounts",
                                owner,
                                "{\"name\":\"夹具账号\",\"provider\":\"openai_compatible\","
                                        + "\"base_url\":\"http://127.0.0.1:18090/v1\",\"secret_id\":\""
                                        + v.get("secret") + "\"}"))
                        .text("id"));
        v.put(
                "spare_provider",
                ok(client.admin(
                                "POST",
                                base + "/provider-accounts",
                                owner,
                                "{\"name\":\"空账号\",\"provider\":\"anthropic\"}"))
                        .text("id"));
        v.put(
                "price_book",
                ok(client.admin(
                                "POST",
                                base + "/price-books",
                                owner,
                                "{\"name\":\"夹具价格表\",\"currency\":\"CNY\"," + PRICES + "}"))
                        .text("id"));
        v.put(
                "model",
                ok(client.admin("POST", base + "/models", owner, model(v.get("provider"), "夹具模型", v.get("price_book"))))
                        .text("id"));
        v.put(
                "spare_model",
                ok(client.admin("POST", base + "/models", owner, model(v.get("provider"), "备用模型", null)))
                        .text("id"));
    }

    /** 价格表请求中的单价字段。 */
    public static final String PRICES =
            "\"prices\":{\"input\":\"0.8\",\"output\":\"2\",\"cache_read\":\"0.16\",\"cache_write\":\"0.8\",\"reasoning\":\"8\"}";

    /**
     * 创建对话模型的请求体。
     *
     * @param providerAccountId 供应商账号
     * @param name 名称
     * @param priceBookId 价格表；可为空
     * @return JSON
     */
    public static String model(String providerAccountId, String name, String priceBookId) {
        return "{\"provider_account_id\":\"" + providerAccountId + "\",\"name\":\"" + name + "\",\"kind\":\"chat\","
                + "\"upstream_model\":\"scripted\",\"context_window\":131072,\"max_output_tokens\":2048"
                + (priceBookId == null ? "" : ",\"price_book_id\":\"" + priceBookId + "\"") + "}";
    }

    private static String bind(
            AdminClient client, String base, String token, String memberId, String role, String projectId) {
        String body = "{\"member_id\":\"" + memberId + "\",\"role\":\"" + role + "\""
                + (projectId == null ? "" : ",\"project_id\":\"" + projectId + "\"") + "}";
        return ok(client.admin("POST", base + "/role-bindings", token, body)).text("id");
    }

    private static Reply ok(Reply reply) {
        assertThat(reply.status()).as(reply.raw()).isBetween(200, 299);
        return reply;
    }
}
