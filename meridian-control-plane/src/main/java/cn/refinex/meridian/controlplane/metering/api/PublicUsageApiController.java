package cn.refinex.meridian.controlplane.metering.api;

import cn.refinex.meridian.api.model.UsageKind;
import cn.refinex.meridian.api.model.UsageReport;
import cn.refinex.meridian.api.model.UsageReportRow;
import cn.refinex.meridian.controlplane.metering.app.UsageReports;
import cn.refinex.meridian.controlplane.openapi.pub.UsageApi;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import cn.refinex.meridian.security.apikey.PublicApiRequests;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开接口 {@code GET /v1/usage}（Usage 标签，ADR-0048）：API Key 或控制台用户（带 {@code Meridian-Project}）经 {@code ApiKeyFilter}
 * 认证，{@code PlatformContext} 已绑定为它的项目；只统计本项目，需要 {@code sessions:read}。
 */
@RestController
public class PublicUsageApiController implements UsageApi {

    private final UsageReports reports;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param reports 报表用例
     * @param request 当前请求（Spring 注入的代理）
     */
    public PublicUsageApiController(UsageReports reports, HttpServletRequest request) {
        this.reports = reports;
        this.request = request;
    }

    @Override
    public ResponseEntity<UsageReport> getUsage(OffsetDateTime from, OffsetDateTime to, String groupBy) {
        PublicApiRequests.require(request, ApiKeyScope.SESSIONS_READ);
        UsageReports.Report report = reports.current(
                from == null ? null : Times.instant(from), to == null ? null : Times.instant(to), dimensions(groupBy));
        return ResponseEntity.ok(new UsageReport(
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
                        .toList()));
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** 逗号分隔的维度；格式已由规范的 {@code pattern} 校验，取值由用例校验。 */
    private static List<String> dimensions(String groupBy) {
        return groupBy == null ? null : List.of(groupBy.split(","));
    }
}
