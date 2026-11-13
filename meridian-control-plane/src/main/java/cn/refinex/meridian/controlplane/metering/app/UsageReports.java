package cn.refinex.meridian.controlplane.metering.app;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.controlplane.metering.domain.MeteringErrorCode;
import cn.refinex.meridian.controlplane.metering.domain.UsageDimension;
import cn.refinex.meridian.controlplane.metering.domain.UsageRange;
import cn.refinex.meridian.controlplane.metering.domain.UsageReportRow;
import cn.refinex.meridian.controlplane.metering.infra.UsageReportRepository;
import cn.refinex.meridian.controlplane.project.app.ProjectService;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 用量报表（ADR-0048 第 9 条）：管理接口的租户与项目报表、公开接口 {@code GET /v1/usage}，同一口径。时间范围左闭右开，默认本月（UTC）
 * 第一天到当前时间，最长 {@link UsageRange#MAX_RANGE}。
 */
@Service
public class UsageReports {

    private final UsageReportRepository repository;
    private final ProjectService projects;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param repository 报表仓储
     * @param projects 项目用例，确认项目存在
     * @param clock 时钟
     */
    public UsageReports(UsageReportRepository repository, ProjectService projects, Clock clock) {
        this.repository = repository;
        this.projects = projects;
        this.clock = clock;
    }

    /**
     * 租户报表（管理接口）。
     *
     * @param tenantId 租户 ID
     * @param projectId 只统计这个项目；可为空
     * @param from 起始时间；可为空
     * @param to 结束时间；可为空
     * @param groupBy 维度；可为空
     * @return 报表
     */
    public Report tenant(String tenantId, String projectId, Instant from, Instant to, Collection<String> groupBy) {
        Query query = query(from, to, groupBy, true);
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> run(projectId, query));
    }

    /**
     * 项目报表（管理接口）。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param from 起始时间；可为空
     * @param to 结束时间；可为空
     * @param groupBy 维度；可为空
     * @return 报表
     */
    public Report project(String tenantId, String projectId, Instant from, Instant to, Collection<String> groupBy) {
        Query query = query(from, to, groupBy, false);
        projects.require(tenantId, projectId);
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> run(projectId, query));
    }

    /**
     * 当前项目的报表（公开接口）：项目取自已绑定的 {@code PlatformContext}（API Key 或 {@code Meridian-Project}）。
     *
     * @param from 起始时间；可为空
     * @param to 结束时间；可为空
     * @param groupBy 维度；可为空
     * @return 报表
     */
    public Report current(Instant from, Instant to, Collection<String> groupBy) {
        Query query = query(from, to, groupBy, false);
        PlatformContext context = PlatformContexts.current();
        return PlatformContexts.call(Scopes.tenant(context.tenantId()), () -> run(context.projectId(), query));
    }

    private Report run(String projectId, Query query) {
        return new Report(
                query.from(),
                query.to(),
                query.dimensions().stream().map(UsageDimension::value).toList(),
                repository.report(projectId, query.from(), query.to(), query.dimensions()));
    }

    private Query query(Instant from, Instant to, Collection<String> groupBy, boolean allowProject) {
        UsageRange range = UsageRange.resolve(from, to, clock.instant());
        List<UsageDimension> dimensions = new ArrayList<>();
        if (groupBy != null) {
            for (String value : groupBy) {
                UsageDimension dimension;
                try {
                    dimension = UsageDimension.of(value.trim());
                } catch (IllegalArgumentException e) {
                    throw invalid("group_by", "未知的聚合维度：" + value);
                }
                if (dimension == UsageDimension.PROJECT && !allowProject) {
                    throw invalid("group_by", "项目报表不能按项目聚合");
                }
                if (!dimensions.contains(dimension)) {
                    dimensions.add(dimension);
                }
            }
        }
        return new Query(range.from(), range.to(), List.copyOf(dimensions));
    }

    private static ValidationException invalid(String field, String message) {
        return new ValidationException(
                MeteringErrorCode.INVALID_QUERY, message, List.of(new FieldViolation(field, message)));
    }

    /**
     * 报表查询的参数。
     *
     * @param from 起始时间
     * @param to 结束时间
     * @param dimensions 维度
     */
    private record Query(Instant from, Instant to, List<UsageDimension> dimensions) {}

    /**
     * 报表。
     *
     * @param from 起始时间（含）
     * @param to 结束时间（不含）
     * @param groupBy 实际使用的维度
     * @param rows 每组的合计
     */
    public record Report(Instant from, Instant to, List<String> groupBy, List<UsageReportRow> rows) {}
}
