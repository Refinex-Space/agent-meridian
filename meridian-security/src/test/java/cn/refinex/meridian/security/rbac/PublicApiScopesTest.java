package cn.refinex.meridian.security.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.security.apikey.ApiKeyScope;
import cn.refinex.meridian.security.oidc.ConsoleUser;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link PublicApiScopes}：控制台用户在公开 API 上的作用域按角色矩阵换算（ADR-0044）。 */
class PublicApiScopesTest {

    private static Set<ApiKeyScope> scopes(Grant grant, String projectId) {
        return PublicApiScopes.granted(new AccessContext(new ConsoleUser("u"), "ten_a", Set.of(grant)), projectId);
    }

    @Test
    void developersAndOwnersCanUseThePlaygroundOthersOnlyRead() {
        Set<ApiKeyScope> all = Set.of(
                ApiKeyScope.SESSIONS_READ,
                ApiKeyScope.SESSIONS_WRITE,
                ApiKeyScope.MODELS_INVOKE,
                ApiKeyScope.FILES_WRITE,
                ApiKeyScope.KNOWLEDGE_READ,
                ApiKeyScope.KNOWLEDGE_WRITE);

        // 所有者另有审批（actions:respond），开发者用 sessions:write 回应（ADR-0053）
        assertThat(scopes(new Grant(Role.TENANT_OWNER, null), "prj_a"))
                .containsAll(all)
                .contains(ApiKeyScope.ACTIONS_RESPOND)
                .hasSize(all.size() + 1);
        assertThat(scopes(new Grant(Role.PROJECT_DEVELOPER, "prj_a"), "prj_a")).isEqualTo(all);
        // 知识库只读（ADR-0051）
        assertThat(scopes(new Grant(Role.TENANT_ADMIN, null), "prj_a"))
                .containsExactlyInAnyOrder(ApiKeyScope.SESSIONS_READ, ApiKeyScope.KNOWLEDGE_READ);
        assertThat(scopes(new Grant(Role.PROJECT_VIEWER, "prj_a"), "prj_a"))
                .containsExactlyInAnyOrder(ApiKeyScope.SESSIONS_READ, ApiKeyScope.KNOWLEDGE_READ);
        // 审批人读会话并回应待处理动作（ADR-0053）
        assertThat(scopes(new Grant(Role.APPROVER, "prj_a"), "prj_a"))
                .containsExactlyInAnyOrder(ApiKeyScope.SESSIONS_READ, ApiKeyScope.ACTIONS_RESPOND);
    }

    @Test
    void projectRolesOnlyCountOnTheirProjectAndPlatformAdminsGetNothing() {
        assertThat(scopes(new Grant(Role.PROJECT_DEVELOPER, "prj_a"), "prj_b")).isEmpty();
        assertThat(scopes(Grant.platformAdmin(), "prj_a")).isEmpty();
    }
}
