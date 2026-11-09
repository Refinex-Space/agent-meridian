package cn.refinex.meridian.security.rbac;

import java.util.Objects;

/**
 * 用户在某个租户中的一个角色绑定（或平台管理员身份）。
 *
 * @param role 角色；必填
 * @param projectId 项目 ID：项目级角色必填，其他角色为空
 */
public record Grant(Role role, String projectId) {

    /**
     * 创建绑定。
     *
     * @throws IllegalArgumentException 项目级角色缺少项目，或其他角色带了项目时
     */
    public Grant {
        Objects.requireNonNull(role, "role");
        if ((role.scope() == Role.Scope.PROJECT) != (projectId != null)) {
            throw new IllegalArgumentException("只有项目级角色带项目 ID：" + role.value() + " " + projectId);
        }
    }

    /**
     * 平台管理员。
     *
     * @return 平台管理员的绑定
     */
    public static Grant platformAdmin() {
        return new Grant(Role.PLATFORM_ADMIN, null);
    }
}
