package cn.refinex.meridian.controlplane.audit.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 一条审计记录（{@code cp.audit_logs}，ADR-0048 第 6 条）。
 *
 * @param id outbox 消息 ID
 * @param occurredAt 发生时间
 * @param tenantId 租户 ID
 * @param projectId 项目 ID；可为空
 * @param action 动作，{@code <资源>.<动作>}
 * @param actorType 操作者类型：{@code console_user}、{@code api_key}、{@code service}；可为空
 * @param actorSubject 操作者；可为空
 * @param resourceType 资源类型；可为空
 * @param resourceId 资源 ID；可为空
 * @param sessionId 相关会话；可为空
 * @param details 补充信息
 */
public record AuditEntry(
        String id,
        Instant occurredAt,
        String tenantId,
        String projectId,
        String action,
        String actorType,
        String actorSubject,
        String resourceType,
        String resourceId,
        String sessionId,
        Map<String, Object> details) {

    /**
     * 创建记录。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public AuditEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(action, "action");
        details = details == null ? Map.of() : details;
    }
}
