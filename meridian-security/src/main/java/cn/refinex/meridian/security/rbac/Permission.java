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
    /** 查看模型目录：供应商账号、模型、价格表（ADR-0045）。 */
    MODEL_READ(Level.TENANT),
    /** 管理模型目录：创建、修改、删除供应商账号与模型，创建价格表与发布版本（ADR-0045）。 */
    MODEL_WRITE(Level.TENANT),
    /** 查看凭据的元数据（名称、版本、使用时间、引用方），任何接口都不返回明文（ADR-0045）。 */
    SECRET_READ(Level.TENANT),
    /** 写入、轮换、删除凭据（ADR-0045）。 */
    SECRET_WRITE(Level.TENANT),
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
    /** 查看本项目的智能体与版本、环境、技能、MCP 服务与项目策略（ADR-0047）。 */
    AGENT_READ(Level.PROJECT),
    /** 管理本项目的智能体（含发布版本）、环境、技能、MCP 服务与项目策略（ADR-0047）。 */
    AGENT_WRITE(Level.PROJECT),
    /** 查看四级预算、预算告警与租户用量报表（ADR-0048）。 */
    BUDGET_READ(Level.TENANT),
    /** 设置、修改、删除预算（ADR-0048）。 */
    BUDGET_WRITE(Level.TENANT),
    /** 查询审计日志（ADR-0048）。 */
    AUDIT_READ(Level.TENANT),
    /** 查看本项目的用量报表（ADR-0048）。 */
    USAGE_READ(Level.PROJECT),
    /** 处理待处理动作（审批）。 */
    PENDING_ACTION_DECIDE(Level.PROJECT),
    /** 公开 API：读取会话、事件与事件流（作用域 {@code sessions:read}，ADR-0044）。 */
    SESSION_READ(Level.PROJECT),
    /** 公开 API：创建会话、发送事件，即 Playground（作用域 {@code sessions:write}，ADR-0044）。 */
    SESSION_WRITE(Level.PROJECT),
    /** 公开 API：模型直通（作用域 {@code models:invoke}，ADR-0044）。 */
    MODEL_INVOKE(Level.PROJECT);

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
