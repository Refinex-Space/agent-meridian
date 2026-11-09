package cn.refinex.meridian.controlplane.support;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个经管理 API 建好的租户：所有者 {@code owner}、第二位所有者 {@code owner2}、管理员 {@code admin}，项目 {@code project} 上的开发者、
 * 观察者、审批人，另一个项目 {@code other_project}，可被移除的成员 {@code spare}（{@code project} 的观察者），以及 {@code project} 下的
 * 一个 API Key。
 *
 * @param values 占位符到 ID 的映射：tenant、project、other_project、spare_member、spare_binding、owner2_member、owner2_binding、
 *     key、key_plaintext
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
