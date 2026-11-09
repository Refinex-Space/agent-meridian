package cn.refinex.meridian.security.rbac;

import java.util.Optional;

/**
 * 控制台用户的角色（架构设计「授权」，ADR-0042）。角色只来自 control-plane 的角色绑定与平台管理员配置，不来自令牌。
 */
public enum Role {
    /** 平台管理员：创建租户、查看租户、修改租户配额；由配置给出，不属于任何租户。 */
    PLATFORM_ADMIN("platform_admin", Scope.PLATFORM),
    /** 租户所有者：租户内的全部操作，包括删除租户与管理所有者。 */
    TENANT_OWNER("tenant_owner", Scope.TENANT),
    /** 租户管理员：管理项目、成员、模型、凭据、预算。 */
    TENANT_ADMIN("tenant_admin", Scope.TENANT),
    /** 项目开发者：管理本项目的智能体、环境、技能、知识库、API Key，可用 Playground。 */
    PROJECT_DEVELOPER("project_developer", Scope.PROJECT),
    /** 项目观察者：本项目只读。 */
    PROJECT_VIEWER("project_viewer", Scope.PROJECT),
    /** 审批人：处理本项目的待处理动作。 */
    APPROVER("approver", Scope.PROJECT);

    /** 角色的作用范围。 */
    public enum Scope {
        /** 平台级，不属于任何租户。 */
        PLATFORM,
        /** 租户级，作用于租户下的所有项目。 */
        TENANT,
        /** 项目级，只作用于绑定的项目。 */
        PROJECT
    }

    private final String value;
    private final Scope scope;

    Role(String value, Scope scope) {
        this.value = value;
        this.scope = scope;
    }

    /**
     * 返回角色在 API 与数据库中的取值。
     *
     * @return 例如 {@code tenant_owner}
     */
    public String value() {
        return value;
    }

    /**
     * 返回角色的作用范围。
     *
     * @return 作用范围
     */
    public Scope scope() {
        return scope;
    }

    /**
     * 按取值查找角色。
     *
     * @param value 角色取值
     * @return 角色；取值未知时为空
     */
    public static Optional<Role> fromValue(String value) {
        for (Role role : values()) {
            if (role.value.equals(value)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
