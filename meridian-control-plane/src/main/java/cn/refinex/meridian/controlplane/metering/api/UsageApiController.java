package cn.refinex.meridian.controlplane.metering.api;

import cn.refinex.meridian.api.admin.model.UsageKind;
import cn.refinex.meridian.api.admin.model.UsageRecordPage;
import cn.refinex.meridian.api.admin.model.UsageReport;
import cn.refinex.meridian.api.admin.model.UsageReportRow;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.metering.app.UsageRecords;
import cn.refinex.meridian.controlplane.metering.app.UsageReports;
import cn.refinex.meridian.controlplane.metering.domain.UsageRecord;
import cn.refinex.meridian.controlplane.metering.domain.UsageRecordFilter;
import cn.refinex.meridian.controlplane.openapi.admin.UsageApi;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用量报表的管理接口（Usage 标签，ADR-0048）：租户报表需要 {@code BUDGET_READ}，项目报表需要 {@code USAGE_READ}（项目开发者与观察者也有）。
 */
@RestController
public class UsageApiController implements UsageApi {

    private final UsageReports reports;
    private final UsageRecords records;

    /**
     * 创建处理器。
     *
     * @param reports 报表用例
     * @param records 明细与导出用例
     */
    public UsageApiController(UsageReports reports, UsageRecords records) {
        this.reports = reports;
        this.records = records;
    }

    @Override
    @RequiresPermission(Permission.BUDGET_READ)
    public ResponseEntity<UsageReport> getTenantUsage(
            String tenantId, OffsetDateTime from, OffsetDateTime to, String groupBy, String projectId) {
        return ResponseEntity.ok(
                toDto(reports.tenant(tenantId, projectId, instant(from), instant(to), dimensions(groupBy))));
    }

    @Override
    @RequiresPermission(Permission.USAGE_READ)
    public ResponseEntity<UsageReport> getProjectUsage(
            String tenantId, String projectId, OffsetDateTime from, OffsetDateTime to, String groupBy) {
        return ResponseEntity.ok(
                toDto(reports.project(tenantId, projectId, instant(from), instant(to), dimensions(groupBy))));
    }

    @Override
    @RequiresPermission(Permission.BUDGET_READ)
    public ResponseEntity<UsageRecordPage> listUsageRecords(
            String tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            String projectId,
            UsageKind kind,
            String modelId,
            String agentId,
            String sessionId,
            String apiKeyId,
            Integer limit,
            String after) {
        UsageRecordFilter filter = filter(projectId, kind, modelId, agentId, sessionId, apiKeyId);
        return ResponseEntity.ok(toPage(
                records.list(tenantId, filter, instant(from), instant(to), PageRequest.of(limit, after), false)));
    }

    @Override
    @RequiresPermission(Permission.BUDGET_READ)
    public ResponseEntity<String> exportUsageRecords(
            String tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            String projectId,
            UsageKind kind,
            String modelId,
            String agentId,
            String sessionId,
            String apiKeyId) {
        UsageRecordFilter filter = filter(projectId, kind, modelId, agentId, sessionId, apiKeyId);
        return csv(tenantId, records.export(tenantId, filter, instant(from), instant(to), false));
    }

    @Override
    @RequiresPermission(Permission.USAGE_READ)
    public ResponseEntity<UsageRecordPage> listProjectUsageRecords(
            String tenantId,
            String projectId,
            OffsetDateTime from,
            OffsetDateTime to,
            UsageKind kind,
            String modelId,
            String agentId,
            String sessionId,
            String apiKeyId,
            Integer limit,
            String after) {
        UsageRecordFilter filter = filter(projectId, kind, modelId, agentId, sessionId, apiKeyId);
        return ResponseEntity.ok(
                toPage(records.list(tenantId, filter, instant(from), instant(to), PageRequest.of(limit, after), true)));
    }

    @Override
    @RequiresPermission(Permission.USAGE_READ)
    public ResponseEntity<String> exportProjectUsageRecords(
            String tenantId,
            String projectId,
            OffsetDateTime from,
            OffsetDateTime to,
            UsageKind kind,
            String modelId,
            String agentId,
            String sessionId,
            String apiKeyId) {
        UsageRecordFilter filter = filter(projectId, kind, modelId, agentId, sessionId, apiKeyId);
        return csv(projectId, records.export(tenantId, filter, instant(from), instant(to), true));
    }

    private static UsageRecordFilter filter(
            String projectId, UsageKind kind, String modelId, String agentId, String sessionId, String apiKeyId) {
        return new UsageRecordFilter(
                projectId,
                kind == null ? null : cn.refinex.meridian.controlplane.metering.domain.UsageKind.of(kind.getValue()),
                modelId,
                agentId,
                sessionId,
                apiKeyId);
    }

    /** CSV 作为附件下载，文件名带范围的 ID。 */
    private static ResponseEntity<String> csv(String scopeId, String body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"usage-" + scopeId + ".csv\"")
                .body(body);
    }

    private static UsageRecordPage toPage(PageResult<UsageRecord> page) {
        return new UsageRecordPage(
                page.data().stream().map(UsageApiController::toDto).toList(), page.hasMore(), page.nextCursor());
    }

    private static cn.refinex.meridian.api.admin.model.UsageRecord toDto(UsageRecord record) {
        return new cn.refinex.meridian.api.admin.model.UsageRecord(
                record.id(),
                Times.utc(record.occurredAt()),
                UsageKind.fromValue(record.kind().value()),
                record.projectId(),
                record.apiKeyId(),
                record.agentId(),
                record.agentVersionId(),
                record.sessionId(),
                record.threadId(),
                record.turnId(),
                record.modelId(),
                record.providerAccountId(),
                record.purpose(),
                record.inputTokens(),
                record.outputTokens(),
                record.cacheReadTokens(),
                record.cacheWriteTokens(),
                record.reasoningTokens(),
                record.quantity().toPlainString(),
                record.priceBookId(),
                record.priceBookVersion(),
                record.currency(),
                text(record.cost()),
                record.attributes());
    }

    private static java.time.Instant instant(OffsetDateTime time) {
        return time == null ? null : Times.instant(time);
    }

    private static UsageReport toDto(UsageReports.Report report) {
        return new UsageReport(
                Times.utc(report.from()),
                Times.utc(report.to()),
                report.groupBy(),
                report.rows().stream()
                        .map(row -> new UsageReportRow(
                                row.day(),
                                row.projectId(),
                                row.modelId(),
                                row.agentId(),
                                row.apiKeyId(),
                                UsageKind.fromValue(row.kind().value()),
                                row.currency(),
                                row.records(),
                                row.quantity().toPlainString(),
                                row.inputTokens(),
                                row.outputTokens(),
                                row.cacheReadTokens(),
                                row.cacheWriteTokens(),
                                row.reasoningTokens(),
                                text(row.cost())))
                        .toList());
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** 逗号分隔的维度；格式已由规范的 {@code pattern} 校验，取值由用例校验。 */
    private static List<String> dimensions(String groupBy) {
        return groupBy == null ? null : List.of(groupBy.split(","));
    }
}
