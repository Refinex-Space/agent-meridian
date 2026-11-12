package cn.refinex.meridian.controlplane.metering.api;

import cn.refinex.meridian.api.admin.model.UsageKind;
import cn.refinex.meridian.api.admin.model.UsageReport;
import cn.refinex.meridian.api.admin.model.UsageReportRow;
import cn.refinex.meridian.controlplane.metering.app.UsageReports;
import cn.refinex.meridian.controlplane.openapi.admin.UsageApi;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用量报表的管理接口（Usage 标签，ADR-0048）：租户报表需要 {@code BUDGET_READ}，项目报表需要 {@code USAGE_READ}（项目开发者与观察者也有）。
 */
@RestController
public class UsageApiController implements UsageApi {

    private final UsageReports reports;

    /**
     * 创建处理器。
     *
     * @param reports 报表用例
     */
    public UsageApiController(UsageReports reports) {
        this.reports = reports;
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
