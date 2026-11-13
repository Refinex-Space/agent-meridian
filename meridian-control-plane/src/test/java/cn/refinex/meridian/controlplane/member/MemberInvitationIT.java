package cn.refinex.meridian.controlplane.member;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.testsupport.TestDatabase;
import cn.refinex.meridian.testsupport.auth.KeycloakRealm;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * OIDC 下的成员（T19b-A04，ADR-0053）：按邮箱邀请并授予角色，用户第一次登录（Keycloak 签发的令牌，邮箱已验证）后获得这个角色；
 * 未验证的邮箱不绑定；用户已是成员时邀请的角色合并过去；创建租户时首个所有者也可以只给邮箱。另有 {@code /me} 与 {@code /me/access}。
 */
class MemberInvitationIT extends ControlPlaneTestBase {

    @Test
    void anInvitedMemberGetsTheGrantedRoleOnFirstLogin() {
        TenantFixture fixture = TenantFixture.create(client());
        String tenant = fixture.get("tenant");
        String project = fixture.get("project");
        String owner = token("owner");
        String base = "/admin/v1/tenants/" + tenant;
        String username = "invitee-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@Example.com";

        // 只给邮箱：成员为 invited，可以授予角色
        Reply invited = client().admin("POST", base + "/members", owner, "{\"email\":\"" + email + "\"}");
        assertThat(invited.status()).isEqualTo(201);
        assertThat(invited.json().get("status").asString()).isEqualTo("invited");
        assertThat(invited.json().get("subject").isNull()).isTrue();
        String memberId = invited.text("id");
        assertThat(client().admin(
                                "POST",
                                base + "/role-bindings",
                                owner,
                                "{\"member_id\":\"" + memberId + "\",\"role\":\"project_developer\",\"project_id\":\""
                                        + project + "\"}")
                        .status())
                .isEqualTo(201);
        // 同一邮箱（不区分大小写）的第二条邀请冲突；什么都不给是 400
        Reply duplicate =
                client().admin("POST", base + "/members", owner, "{\"email\":\"" + email.toLowerCase() + "\"}");
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.text("code")).isEqualTo("member.already_exists");
        Reply empty = client().admin("POST", base + "/members", owner, "{\"display_name\":\"无名\"}");
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.text("code")).isEqualTo("member.identity_required");

        // 用户在 IdP 中用这个邮箱（已验证）登录：第一次请求就有了角色
        KeycloakRealm.userWithEmail(username, email.toLowerCase(), true);
        String user = KeycloakRealm.token(username);
        Reply me = client().admin("GET", "/admin/v1/me", user, null);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.text("subject")).isEqualTo(username);
        assertThat(me.text("email")).isEqualTo(email.toLowerCase());
        assertThat(me.json().get("platform_admin").asBoolean()).isFalse();
        JsonNode membership = membership(me, tenant);
        assertThat(membership.get("member_id").asString()).isEqualTo(memberId);
        assertThat(membership.get("role_bindings").get(0).get("role").asString())
                .isEqualTo("project_developer");
        assertThat(membership.get("role_bindings").get(0).get("project_id").asString())
                .isEqualTo(project);
        assertThat(client().admin("GET", base + "/projects/" + project + "/agents", user, null)
                        .status())
                .isEqualTo(200);
        assertThat(client().admin("GET", base + "/projects/" + fixture.get("other_project") + "/agents", user, null)
                        .status())
                .as("项目级角色只作用于绑定的项目")
                .isEqualTo(403);

        Reply member = client().admin("GET", base + "/members/" + memberId, owner, null);
        assertThat(member.text("status")).isEqualTo("active");
        assertThat(member.text("subject")).isEqualTo(username);
        assertThat(auditActions(tenant, memberId)).contains("member.added", "member.bound");

        // 公开 API 的作用域随之生效
        Reply access =
                client().admin("GET", "/admin/v1/me/access?tenant_id=" + tenant + "&project_id=" + project, user, null);
        assertThat(texts(access.json().get("permissions")))
                .contains("agent_write", "session_write")
                .doesNotContain("member_write", "pending_action_decide");
        assertThat(texts(access.json().get("scopes"))).contains("sessions:write", "knowledge:write");
    }

    @Test
    void unverifiedEmailsDoNotBindAndExistingMembersAbsorbTheInvitation() {
        TenantFixture fixture = TenantFixture.create(client());
        String tenant = fixture.get("tenant");
        String project = fixture.get("project");
        String owner = token("owner");
        String base = "/admin/v1/tenants/" + tenant;

        // 未验证的邮箱：邀请保持 invited，用户没有角色
        String unverified = "unverified-" + UUID.randomUUID().toString().substring(0, 8);
        String invitation = client().admin(
                        "POST", base + "/members", owner, "{\"email\":\"" + unverified + "@example.com\"}")
                .text("id");
        KeycloakRealm.userWithEmail(unverified, unverified + "@example.com", false);
        String unverifiedToken = KeycloakRealm.token(unverified);
        assertThat(client().admin("GET", "/admin/v1/me", unverifiedToken, null)
                        .json()
                        .get("memberships"))
                .isEmpty();
        assertThat(client().admin("GET", base, unverifiedToken, null).status()).isEqualTo(403);
        assertThat(client().admin("GET", base + "/members/" + invitation, owner, null)
                        .text("status"))
                .isEqualTo("invited");

        // 已是成员（按用户标识添加的 viewer）又被按邮箱邀请为审批人：登录后角色合并到原成员，邀请删除
        String viewerEmail = "viewer@test.meridian.local";
        String extra = client().admin("POST", base + "/members", owner, "{\"email\":\"" + viewerEmail + "\"}")
                .text("id");
        client().admin(
                        "POST",
                        base + "/role-bindings",
                        owner,
                        "{\"member_id\":\"" + extra + "\",\"role\":\"approver\",\"project_id\":\"" + project + "\"}");
        Reply viewerAccess = client().admin(
                        "GET",
                        "/admin/v1/me/access?tenant_id=" + tenant + "&project_id=" + project,
                        token("viewer"),
                        null);
        assertThat(texts(viewerAccess.json().get("permissions"))).contains("pending_action_decide", "agent_read");
        assertThat(texts(viewerAccess.json().get("scopes"))).contains("actions:respond", "sessions:read");
        assertThat(client().admin("GET", base + "/members/" + extra, owner, null)
                        .status())
                .isEqualTo(404);
        assertThat(auditActions(tenant, extra)).contains("member.bound");
    }

    @Test
    void aTenantCanBeCreatedForAnOwnerKnownOnlyByEmail() {
        String username = "founder-" + UUID.randomUUID().toString().substring(0, 8);
        Reply tenant = client().admin(
                        "POST",
                        "/admin/v1/tenants",
                        token(PLATFORM),
                        "{\"name\":\"邮箱租户\",\"owner\":{\"email\":\"" + username + "@example.com\"}}");
        assertThat(tenant.status()).isEqualTo(201);
        String tenantId = tenant.text("id");

        KeycloakRealm.userWithEmail(username, username + "@example.com", true);
        String founder = KeycloakRealm.token(username);
        assertThat(client().admin("DELETE", "/admin/v1/tenants/" + tenantId, founder, null)
                        .status())
                .as("首次登录后是所有者")
                .isEqualTo(204);

        Reply access = client().admin("GET", "/admin/v1/me/access?tenant_id=" + tenantId, token("outsider"), null);
        assertThat(access.status()).isEqualTo(200);
        assertThat(access.json().get("permissions")).isEmpty();
        assertThat(access.json().get("scopes")).isEmpty();
        assertThat(client().admin("GET", "/admin/v1/me", token(PLATFORM), null)
                        .json()
                        .get("platform_admin")
                        .asBoolean())
                .isTrue();
    }

    private static JsonNode membership(Reply me, String tenantId) {
        for (JsonNode membership : me.json().get("memberships")) {
            if (membership.get("tenant_id").asString().equals(tenantId)) {
                return membership;
            }
        }
        throw new AssertionError("没有租户 " + tenantId + " 的成员身份：" + me.raw());
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    private static List<String> auditActions(String tenantId, String memberId) {
        return JdbcClient.create(TestDatabase.migratorDataSource())
                .sql("""
                        SELECT payload->>'action' FROM cp.outbox
                         WHERE topic = 'audit' AND payload->>'tenant_id' = ? AND payload->>'resource_id' = ?
                         ORDER BY id
                        """)
                .param(tenantId)
                .param(memberId)
                .query(String.class)
                .list();
    }
}
