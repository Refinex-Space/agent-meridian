package cn.refinex.meridian.security.rbac;

/**
 * 管理操作的类别：角色矩阵的列（{@link RoleMatrix}）。处理器方法用 {@link RequiresPermission} 声明所需的类别。
 *
 * <p>每个类别有一个层级，决定哪些角色绑定参与判定，见 {@link AccessContext#permits}。
 */
public enum Permission {
    /** 任何已认证的控制台用户，例如列出自己所在的租户。 */
    CONSOLE_ACCESS(Level.AUTHENTICATED),
    /** 创建租户。 */
    TENANT_CREATE(Level.PLATFORM),
    /** 列出平台上的全部租户。 */
    TENANT_LIST_ALL(Level.PLATFORM),
    /** 修改租户配额（并发配额、事件保留天数）：平台资源的分配，租户自己不能改。 */
    TENANT_QUOTA_UPDATE(Level.PLATFORM),
    /** 查看租户、租户设置与项目列表。 */
    TENANT_READ(Level.TENANT),
    /** 修改租户名称与租户设置。 */
    TENANT_UPDATE(Level.TENANT),
    /** 删除租户。 */
    TENANT_DELETE(Level.TENANT),
    /** 查看成员与角色绑定。 */
    MEMBER_READ(Level.TENANT),
    /** 添加、修改、移除成员，授予与撤销角色。 */
    MEMBER_WRITE(Level.TENANT),
    /** 授予、撤销租户所有者，移除所有者成员：在 {@link #MEMBER_WRITE} 之外另需的权限。 */
    OWNER_ROLE_MANAGE(Level.TENANT),
    /** 创建项目。 */
    PROJECT_CREATE(Level.TENANT),
    /** 查看项目。 */
    PROJECT_READ(Level.PROJECT),
    /** 修改项目。 */
    PROJECT_UPDATE(Level.PROJECT),
    /** 删除项目。 */
    PROJECT_DELETE(Level.PROJECT),
    /** 查看 API Key 的元数据。 */
    API_KEY_READ(Level.PROJECT),
    /** 创建、修改、吊销 API Key。 */
    API_KEY_WRITE(Level.PROJECT),
    /** 处理待处理动作（审批）。 */
    PENDING_ACTION_DECIDE(Level.PROJECT);

    /** 判定层级。 */
    public enum Level {
        /** 只要求已认证。 */
        AUTHENTICATED,
        /** 只看平台级角色。 */
        PLATFORM,
        /** 看平台级角色与该租户中的全部角色绑定（项目级角色也算租户成员）。 */
        TENANT,
        /** 看平台级角色、该租户的租户级角色，以及绑定到该项目的项目级角色。 */
        PROJECT
    }

    private final Level level;

    Permission(Level level) {
        this.level = level;
    }

    /**
     * 返回判定层级。
     *
     * @return 层级
     */
    public Level level() {
        return level;
    }
}
