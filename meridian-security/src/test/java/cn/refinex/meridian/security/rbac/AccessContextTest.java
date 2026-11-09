package cn.refinex.meridian.security.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.security.oidc.ConsoleUser;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link AccessContext}：角色的作用范围决定哪些绑定参与判定（ADR-0042）。矩阵本身由 control-plane 的表驱动测试逐格覆盖。 */
class AccessContextTest {

    private static final ConsoleUser USER = new ConsoleUser("u-1");

    @Test
    void projectRolesOnlyApplyToTheirOwnProject() {
        AccessContext developer = new AccessContext(USER, "ten_a", Set.of(new Grant(Role.PROJECT_DEVELOPER, "prj_a")));

        assertThat(developer.permits(Permission.API_KEY_WRITE, "prj_a")).isTrue();
        assertThat(developer.permits(Permission.API_KEY_WRITE, "prj_b")).isFalse();
        // 项目级角色在租户级的只读操作上算作租户成员
        assertThat(developer.permits(Permission.TENANT_READ, null)).isTrue();
        assertThat(developer.permits(Permission.PROJECT_CREATE, null)).isFalse();
    }

    @Test
    void tenantRolesApplyToEveryProjectOfTheTenant() {
        AccessContext admin = new AccessContext(USER, "ten_a", Set.of(new Grant(Role.TENANT_ADMIN, null)));

        assertThat(admin.permits(Permission.API_KEY_WRITE, "prj_a")).isTrue();
        assertThat(admin.permits(Permission.API_KEY_WRITE, "prj_b")).isTrue();
        assertThat(admin.permits(Permission.OWNER_ROLE_MANAGE, null)).isFalse();
        assertThat(admin.permits(Permission.TENANT_DELETE, null)).isFalse();
    }

    @Test
    void platformPermissionsOnlyCountThePlatformAdmin() {
        AccessContext owner = new AccessContext(USER, "ten_a", Set.of(new Grant(Role.TENANT_OWNER, null)));
        AccessContext platform = new AccessContext(USER, "ten_a", Set.of(Grant.platformAdmin()));

        assertThat(owner.permits(Permission.TENANT_QUOTA_UPDATE, null)).isFalse();
        assertThat(platform.permits(Permission.TENANT_QUOTA_UPDATE, null)).isTrue();
        assertThat(platform.permits(Permission.TENANT_READ, null)).isTrue();
        assertThat(platform.permits(Permission.PROJECT_READ, "prj_a")).isFalse();
    }

    @Test
    void anAuthenticatedUserWithoutGrantsOnlyHasConsoleAccess() {
        AccessContext nobody = new AccessContext(USER, "ten_a", Set.of());

        assertThat(nobody.permits(Permission.CONSOLE_ACCESS, null)).isTrue();
        for (Permission permission : Permission.values()) {
            if (permission != Permission.CONSOLE_ACCESS) {
                assertThat(nobody.permits(permission, "prj_a"))
                        .as(permission.name())
                        .isFalse();
            }
        }
        assertThatThrownBy(() -> nobody.require(Permission.TENANT_READ, null)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void ownersHoldEveryTenantAndProjectPermission() {
        for (Permission permission : Permission.values()) {
            boolean tenantOrProject =
                    permission.level() == Permission.Level.TENANT || permission.level() == Permission.Level.PROJECT;
            assertThat(RoleMatrix.allows(Role.TENANT_OWNER, permission))
                    .as(permission.name())
                    .isEqualTo(tenantOrProject);
        }
    }

    @Test
    void projectRolesRequireAProjectAndOtherRolesRejectOne() {
        assertThatThrownBy(() -> new Grant(Role.APPROVER, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Grant(Role.TENANT_OWNER, "prj_a")).isInstanceOf(IllegalArgumentException.class);
    }
}
