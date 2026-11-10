package cn.refinex.meridian.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.testsupport.auth.KeycloakRealm;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 默认的声明配置（T14b-A01，ADR-0043 第 5 条）：用户标识取 {@code sub}（Keycloak 的用户 ID），平台管理员按主体标识列表判定。
 * 与基类的配置（{@code preferred_username} 加按组判定）对照，证明声明映射只靠配置、不依赖 Keycloak 的特有行为。
 */
class OidcSubjectClaimIT extends ControlPlaneTestBase {

    /** 平台管理员：按 sub 登记，不在平台管理员组中。 */
    static final String ADMIN = "sub-config-admin";

    /** 在平台管理员组中，但这个配置不按组判定。 */
    static final String GROUP_MEMBER = "sub-config-group-member";

    /** 新租户的所有者。 */
    static final String OWNER = "sub-config-owner";

    @DynamicPropertySource
    static void subjectClaim(DynamicPropertyRegistry registry) {
        registry.add("meridian.security.oidc.user-id-claim", () -> "sub");
        registry.add("meridian.control-plane.platform-admins[0]", () -> KeycloakRealm.user(ADMIN));
        registry.add("meridian.control-plane.platform-admin-groups[0]", () -> "no-such-group");
    }

    @Test
    void theSubClaimIdentifiesUsersAndPlatformAdminsComeFromTheSubjectList() {
        KeycloakRealm.user(GROUP_MEMBER, KeycloakRealm.PLATFORM_ADMIN_GROUP);
        String ownerSub = KeycloakRealm.user(OWNER);

        Reply created = client().admin(
                        "POST",
                        "/admin/v1/tenants",
                        KeycloakRealm.token(ADMIN),
                        "{\"name\":\"sub 配置\",\"owner\":{\"subject\":\"" + ownerSub + "\"}}");
        Reply byGroup = client().admin(
                        "POST",
                        "/admin/v1/tenants",
                        KeycloakRealm.token(GROUP_MEMBER),
                        "{\"name\":\"不允许\",\"owner\":{\"subject\":\"" + ownerSub + "\"}}");

        assertThat(created.status()).isEqualTo(201);
        assertThat(byGroup.status()).isEqualTo(403);
        // 所有者按 sub 绑定：他的令牌能读这个租户
        Reply read = client().admin("GET", "/admin/v1/tenants/" + created.text("id"), KeycloakRealm.token(OWNER), null);
        assertThat(read.status()).isEqualTo(200);
    }
}
