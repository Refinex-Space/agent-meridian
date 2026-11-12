package cn.refinex.meridian.controlplane.audit.domain;

import java.time.Instant;

/**
 * 审计查询的条件（ADR-0048 第 6 条）；为空的条件不参与过滤，条件之间是"且"。
 *
 * @param from 起始时间（含）
 * @param to 结束时间（不含）
 * @param action 动作
 * @param actor 操作者
 * @param resourceType 资源类型
 * @param resourceId 资源 ID
 * @param projectId 项目 ID
 * @param sessionId 会话 ID
 */
public record AuditFilter(
        Instant from,
        Instant to,
        String action,
        String actor,
        String resourceType,
        String resourceId,
        String projectId,
        String sessionId) {

    /**
     * 换上时间范围。
     *
     * @param start 起始时间
     * @param end 结束时间
     * @return 新的条件
     */
    public AuditFilter between(Instant start, Instant end) {
        return new AuditFilter(start, end, action, actor, resourceType, resourceId, projectId, sessionId);
    }
}
