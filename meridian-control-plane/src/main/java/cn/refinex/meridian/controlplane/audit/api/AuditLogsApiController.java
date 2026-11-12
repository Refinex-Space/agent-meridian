package cn.refinex.meridian.controlplane.audit.api;

import cn.refinex.meridian.api.admin.model.AuditActor;
import cn.refinex.meridian.api.admin.model.AuditLog;
import cn.refinex.meridian.api.admin.model.AuditLogPage;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.audit.app.AuditQuery;
import cn.refinex.meridian.controlplane.audit.domain.AuditEntry;
import cn.refinex.meridian.controlplane.audit.domain.AuditFilter;
import cn.refinex.meridian.controlplane.openapi.admin.AuditLogsApi;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import java.time.OffsetDateTime;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 审计查询的管理接口（AuditLogs 标签，ADR-0048）：只做授权声明、decode → call → map。 */
@RestController
public class AuditLogsApiController implements AuditLogsApi {

    private final AuditQuery audits;

    /**
     * 创建处理器。
     *
     * @param audits 审计查询
     */
    public AuditLogsApiController(AuditQuery audits) {
        this.audits = audits;
    }

    @Override
    @RequiresPermission(Permission.AUDIT_READ)
    public ResponseEntity<AuditLogPage> listAuditLogs(
            String tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            String action,
            String actor,
            String resourceType,
            String resourceId,
            String projectId,
            String sessionId,
            Integer limit,
            String after) {
        var page = audits.search(
                tenantId,
                new AuditFilter(
                        from == null ? null : Times.instant(from),
                        to == null ? null : Times.instant(to),
                        action,
                        actor,
                        resourceType,
                        resourceId,
                        projectId,
                        sessionId),
                PageRequest.of(limit, after));
        return ResponseEntity.ok(new AuditLogPage(
                page.data().stream().map(AuditLogsApiController::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    private static AuditLog toDto(AuditEntry entry) {
        return new AuditLog(
                entry.id(),
                Times.utc(entry.occurredAt()),
                entry.action(),
                entry.projectId(),
                entry.actorType() == null ? null : new AuditActor(entry.actorType(), entry.actorSubject()),
                entry.resourceType(),
                entry.resourceId(),
                entry.sessionId(),
                entry.details());
    }
}
