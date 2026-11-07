package cn.refinex.meridian.runtime.session.domain;

import java.time.Instant;

/**
 * 读出的线程。命名避开 {@link java.lang.Thread}。
 *
 * @param id 线程 ID
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param sessionId 所属会话
 * @param parentThreadId 父线程 ID；主线程为空
 * @param parentToolUseId 拉起子线程的工具调用 ID；主线程为空
 * @param agentName 执行本线程的智能体名
 * @param status 线程状态
 * @param createdAt 创建时间
 */
public record SessionThread(
        String id,
        String tenantId,
        String projectId,
        String sessionId,
        String parentThreadId,
        String parentToolUseId,
        String agentName,
        SessionStatus status,
        Instant createdAt) {

    /**
     * 是否为主线程。
     *
     * @return 没有父线程时为 true
     */
    public boolean isMain() {
        return parentThreadId == null;
    }
}
