package cn.refinex.meridian.common.context;

/**
 * 一次请求或一轮执行的平台上下文：租户、项目、会话、线程、轮次、API Key、租约 epoch、预算句柄、值守方式、终端用户与控制台用户，
 * 字段按架构设计「身份映射」中的类型键 {@code PlatformContext} 一行。
 *
 * <p>它由 Web 入口（{@code meridian-security}）或轮次 Worker 填充，经 {@link PlatformContexts} 传递：
 * MVC 服务用 {@code ScopedValue}，runtime 用 Reactor Context。租户作用域仓储从它读取
 * {@code tenant_id} 与 {@code project_id}（架构设计「租户隔离」）。
 *
 * <p>除 {@code tenantId} 外的字段都可为空：不同入口能确定的范围不同，例如控制台的租户级操作没有项目，
 * 会话 API 的请求在进入轮次之前没有线程与轮次。
 *
 * @param tenantId 租户 ID（{@code ten_…}），必填
 * @param projectId 项目 ID（{@code prj_…}）；可为空，表示租户级操作
 * @param sessionId 会话 ID（{@code ses_…}）；可为空，表示与会话无关
 * @param threadId 线程 ID（{@code thr_…}）；可为空，表示不在某条线程上执行
 * @param turnId 轮次 ID（{@code trn_…}）；可为空，表示不在某一轮中执行
 * @param apiKeyId 发起请求的 API Key ID（{@code key_…}）；可为空，表示由控制台用户或平台内部发起
 * @param leaseEpoch 当前执行持有的租约 epoch（架构设计「租约与 fencing」）；可为空，表示不在轮次执行中
 * @param budgetHandle 预算句柄：本轮预扣与结算所依据的预算标识，由模型层解释（架构设计「计量与预算」）；可为空
 * @param interaction 会话的值守方式；可为空，表示与会话无关
 * @param endUserId 会话的终端用户 ID；可为空，表示会话没有终端用户或与会话无关。工作区命名空间
 *     {@code [tenant_id, project_id, end_user_id, session_id]} 从这里取（架构设计「文件系统装配」）
 * @param userId 发起请求的控制台用户的主体标识（OIDC 令牌中配置的用户标识声明，ADR-0044）；可为空，表示由 API Key 或平台内部
 *     发起。与 {@code endUserId} 无关：后者是业务系统的终端用户
 */
public record PlatformContext(
        String tenantId,
        String projectId,
        String sessionId,
        String threadId,
        String turnId,
        String apiKeyId,
        Long leaseEpoch,
        String budgetHandle,
        Interaction interaction,
        String endUserId,
        String userId) {

    /**
     * 创建上下文。
     *
     * @throws IllegalArgumentException 缺少租户 ID 时
     */
    public PlatformContext {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("PlatformContext 必须包含 tenantId");
        }
    }

    /**
     * 发起本次请求的主体：API Key ID，或 {@code user:} 加控制台用户的主体标识（ADR-0044）。幂等键的作用域、待处理动作的
     * {@code decided_by} 用它，两种主体不会相撞（API Key ID 以 {@code key_} 开头）。
     *
     * @return 主体；两者都没有（平台内部发起）时为空
     */
    public String actor() {
        if (apiKeyId != null) {
            return apiKeyId;
        }
        return userId == null ? null : "user:" + userId;
    }

    /**
     * 公开 API 幂等键的作用域主体：API Key 只属于一个项目，取 Key ID；控制台用户可以带不同的 {@code Meridian-Project} 调用，取
     * {@link #actor()} 再加项目，否则同一用户同键同请求体在另一个项目重放会拿到前一个项目的结果（R2 审查 I-05）。
     *
     * @return 主体；两者都没有时为空
     */
    public String idempotencyPrincipal() {
        if (apiKeyId != null || userId == null) {
            return actor();
        }
        return actor() + "@" + projectId;
    }

    /**
     * 新建构建器。
     *
     * @return 空的构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 以当前值为初始值的构建器，用于在已有上下文上补充字段，例如进入轮次时补上线程、轮次与 epoch。
     *
     * @return 构建器
     */
    public Builder toBuilder() {
        return new Builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .sessionId(sessionId)
                .threadId(threadId)
                .turnId(turnId)
                .apiKeyId(apiKeyId)
                .leaseEpoch(leaseEpoch)
                .budgetHandle(budgetHandle)
                .interaction(interaction)
                .endUserId(endUserId)
                .userId(userId);
    }

    /** {@link PlatformContext} 的构建器，字段含义见记录组件说明。 */
    public static final class Builder {
        private String tenantId;
        private String projectId;
        private String sessionId;
        private String threadId;
        private String turnId;
        private String apiKeyId;
        private Long leaseEpoch;
        private String budgetHandle;
        private Interaction interaction;
        private String endUserId;
        private String userId;

        private Builder() {}

        /**
         * 设置租户 ID。
         *
         * @param value 租户 ID
         * @return 本构建器
         */
        public Builder tenantId(String value) {
            this.tenantId = value;
            return this;
        }

        /**
         * 设置项目 ID。
         *
         * @param value 项目 ID，可为空
         * @return 本构建器
         */
        public Builder projectId(String value) {
            this.projectId = value;
            return this;
        }

        /**
         * 设置会话 ID。
         *
         * @param value 会话 ID，可为空
         * @return 本构建器
         */
        public Builder sessionId(String value) {
            this.sessionId = value;
            return this;
        }

        /**
         * 设置线程 ID。
         *
         * @param value 线程 ID，可为空
         * @return 本构建器
         */
        public Builder threadId(String value) {
            this.threadId = value;
            return this;
        }

        /**
         * 设置轮次 ID。
         *
         * @param value 轮次 ID，可为空
         * @return 本构建器
         */
        public Builder turnId(String value) {
            this.turnId = value;
            return this;
        }

        /**
         * 设置 API Key ID。
         *
         * @param value API Key ID，可为空
         * @return 本构建器
         */
        public Builder apiKeyId(String value) {
            this.apiKeyId = value;
            return this;
        }

        /**
         * 设置租约 epoch。
         *
         * @param value 租约 epoch，可为空
         * @return 本构建器
         */
        public Builder leaseEpoch(Long value) {
            this.leaseEpoch = value;
            return this;
        }

        /**
         * 设置预算句柄。
         *
         * @param value 预算句柄，可为空
         * @return 本构建器
         */
        public Builder budgetHandle(String value) {
            this.budgetHandle = value;
            return this;
        }

        /**
         * 设置值守方式。
         *
         * @param value 值守方式，可为空
         * @return 本构建器
         */
        public Builder interaction(Interaction value) {
            this.interaction = value;
            return this;
        }

        /**
         * 设置终端用户 ID。
         *
         * @param value 终端用户 ID，可为空
         * @return 本构建器
         */
        public Builder endUserId(String value) {
            this.endUserId = value;
            return this;
        }

        /**
         * 设置控制台用户的主体标识。
         *
         * @param value 主体标识，可为空
         * @return 本构建器
         */
        public Builder userId(String value) {
            this.userId = value;
            return this;
        }

        /**
         * 构建上下文。
         *
         * @return 不可变的上下文
         * @throws IllegalArgumentException 缺少租户 ID 时
         */
        public PlatformContext build() {
            return new PlatformContext(
                    tenantId,
                    projectId,
                    sessionId,
                    threadId,
                    turnId,
                    apiKeyId,
                    leaseEpoch,
                    budgetHandle,
                    interaction,
                    endUserId,
                    userId);
        }
    }
}
