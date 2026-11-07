package cn.refinex.meridian.engine.agentscope.turn;

import java.util.Objects;

/**
 * 平台身份到 AgentScope 身份的映射，按架构设计「身份映射」表：{@code RuntimeContext.sessionId} 是线程 ID，
 * {@code RuntimeContext.userId} 是 {@code {project_id}:{end_user_id}}，没有终端用户时为 {@code {project_id}:_}。
 *
 * <p>AgentScope 的状态键是 {@code (userId, sessionId, key)}，没有智能体维度：用线程 ID 作 {@code sessionId} 让主智能体与
 * 子智能体的状态互不覆盖，用项目加终端用户作 {@code userId} 让数据按项目与终端用户隔离。
 */
public final class AgentScopeIdentity {

    /** 没有终端用户时 {@code userId} 中终端用户的占位。架构设计「身份映射」规定为下划线。 */
    static final String NO_END_USER = "_";

    private AgentScopeIdentity() {}

    /**
     * 返回 AgentScope 的 {@code userId}。
     *
     * @param projectId 项目 ID（{@code prj_…}）
     * @param endUserId 终端用户 ID；可为空，表示会话没有终端用户
     * @return {@code {project_id}:{end_user_id}}，或 {@code {project_id}:_}
     */
    public static String userId(String projectId, String endUserId) {
        Objects.requireNonNull(projectId, "projectId");
        return projectId + ":" + (endUserId == null || endUserId.isBlank() ? NO_END_USER : endUserId);
    }
}
