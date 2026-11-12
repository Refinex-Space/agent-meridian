package cn.refinex.meridian.controlplane.member;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 内部接口 {@code project-access:resolve}（ADR-0044）：控制台用户在项目上的公开 API 作用域按角色矩阵换算。 */
class ProjectAccessIT extends ControlPlaneTestBase {

    private Reply resolve(String subject, String projectId, String credential) {
        return client().internal(
                        "POST",
                        "/internal/v1/project-access:resolve",
                        credential,
                        "{\"subject\":\"" + subject + "\",\"project_id\":\"" + projectId + "\"}");
    }

    private static List<String> scopes(Reply reply) {
        List<String> scopes = new ArrayList<>();
        for (JsonNode scope : reply.json().get("scopes")) {
            scopes.add(scope.asString());
        }
        return scopes;
    }

    @Test
    void scopesFollowTheRoleMatrix() {
        TenantFixture fixture = TenantFixture.create(client());
        String token = serviceToken();
        String project = fixture.get("project");

        Reply developer = resolve("developer", project, token);
        assertThat(developer.status()).isEqualTo(200);
        assertThat(developer.text("tenant_id")).isEqualTo(fixture.get("tenant"));
        assertThat(scopes(developer))
                .containsExactly("files:write", "models:invoke", "sessions:read", "sessions:write");
        assertThat(scopes(resolve("owner", project, token)))
                .containsExactly("files:write", "models:invoke", "sessions:read", "sessions:write");
        assertThat(scopes(resolve("viewer", project, token))).containsExactly("sessions:read");
        assertThat(scopes(resolve("approver", project, token))).containsExactly("sessions:read");
        // 项目级角色只作用于绑定的项目；不是成员没有作用域
        assertThat(scopes(resolve("developer", fixture.get("other_project"), token)))
                .isEmpty();
        assertThat(scopes(resolve("outsider", project, token))).isEmpty();
    }

    @Test
    void unknownAndDeletedProjectsAreNotFound() {
        TenantFixture fixture = TenantFixture.create(client());
        String token = serviceToken();
        assertThat(client().admin(
                                "DELETE",
                                "/admin/v1/tenants/" + fixture.get("tenant") + "/projects/"
                                        + fixture.get("other_project"),
                                token("owner"),
                                null)
                        .status())
                .isEqualTo(204);

        for (String project : new String[] {"prj_missing", fixture.get("other_project")}) {
            Reply reply = resolve("owner", project, token);
            assertThat(reply.status()).isEqualTo(404);
            assertThat(reply.text("code")).isEqualTo("project.not_found");
        }
    }

    @Test
    void consoleTokensCannotCallTheInternalEndpoint() {
        Reply reply = resolve("owner", "prj_missing", token("owner"));

        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.text("code")).isEqualTo("auth.invalid_service_credential");
    }
}
