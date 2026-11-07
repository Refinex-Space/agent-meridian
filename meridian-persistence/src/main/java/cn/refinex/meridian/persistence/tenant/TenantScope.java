package cn.refinex.meridian.persistence.tenant;

/**
 * 租户作用域仓储的作用域，见架构设计「租户隔离」。
 */
public enum TenantScope {
    /** 项目级（默认）：每条语句都限定 {@code tenant_id} 与 {@code project_id}，用于绝大多数业务数据。 */
    PROJECT,
    /** 租户级：每条语句只限定 {@code tenant_id}，用于成员、角色绑定、供应商账号、租户设置等租户级数据。 */
    TENANT
}
