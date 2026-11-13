package cn.refinex.meridian.controlplane.metering.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.metering.domain.MeteringErrorCode;
import cn.refinex.meridian.controlplane.metering.domain.UsageRange;
import cn.refinex.meridian.controlplane.metering.domain.UsageRecord;
import cn.refinex.meridian.controlplane.metering.domain.UsageRecordFilter;
import cn.refinex.meridian.controlplane.metering.infra.UsageRecordRepository;
import cn.refinex.meridian.controlplane.project.app.ProjectService;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * 用量明细与 CSV 导出（D68，ADR-0053）：与用量报表同一份账本、同样的时间范围规则，明细的合计等于报表。线程安全：无状态。
 */
@Service
public class UsageRecords {

    /** 一次导出的行数上限：超过时要求缩小范围，CSV 在内存中拼成（约 25 MB）。 */
    public static final int EXPORT_LIMIT = 100_000;

    /** CSV 的列，与 {@code UsageRecord} 的字段同名、同序。 */
    static final List<String> COLUMNS = List.of(
            "id",
            "occurred_at",
            "kind",
            "project_id",
            "api_key_id",
            "agent_id",
            "agent_version_id",
            "session_id",
            "thread_id",
            "turn_id",
            "model_id",
            "provider_account_id",
            "purpose",
            "input_tokens",
            "output_tokens",
            "cache_read_tokens",
            "cache_write_tokens",
            "reasoning_tokens",
            "quantity",
            "price_book_id",
            "price_book_version",
            "currency",
            "cost",
            "attributes");

    private final UsageRecordRepository repository;
    private final ProjectService projects;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param repository 明细仓储
     * @param projects 项目用例，确认项目存在
     * @param clock 时钟
     */
    public UsageRecords(UsageRecordRepository repository, ProjectService projects, Clock clock) {
        this.repository = repository;
        this.projects = projects;
        this.clock = clock;
    }

    /**
     * 一页明细。项目级接口给出 {@code projectId} 时先确认项目存在。
     *
     * @param tenantId 租户 ID
     * @param filter 筛选条件
     * @param from 起始时间；可为空
     * @param to 结束时间；可为空
     * @param page 分页参数
     * @param requireProject 是否要求 {@code filter.projectId()} 是本租户的项目（项目级接口）
     * @return 一页明细
     */
    public PageResult<UsageRecord> list(
            String tenantId,
            UsageRecordFilter filter,
            Instant from,
            Instant to,
            PageRequest page,
            boolean requireProject) {
        UsageRange range = UsageRange.resolve(from, to, clock.instant());
        if (requireProject) {
            projects.require(tenantId, filter.projectId());
        }
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> PageResult.of(
                        repository.page(filter, range, page),
                        page,
                        record -> Cursor.of(record.occurredAt().toString(), record.id())));
    }

    /**
     * 导出 CSV（RFC 4180：逗号分隔、CRLF 换行、含逗号引号换行的字段加双引号），带表头，新的在前。
     *
     * @param tenantId 租户 ID
     * @param filter 筛选条件
     * @param from 起始时间；可为空
     * @param to 结束时间；可为空
     * @param requireProject 是否要求 {@code filter.projectId()} 是本租户的项目
     * @return CSV 文本
     * @throws ValidationException 超过 {@link #EXPORT_LIMIT} 行时（{@code usage.export_too_large}）
     */
    public String export(String tenantId, UsageRecordFilter filter, Instant from, Instant to, boolean requireProject) {
        UsageRange range = UsageRange.resolve(from, to, clock.instant());
        if (requireProject) {
            projects.require(tenantId, filter.projectId());
        }
        List<UsageRecord> records =
                PlatformContexts.call(Scopes.tenant(tenantId), () -> repository.list(filter, range, EXPORT_LIMIT + 1));
        if (records.size() > EXPORT_LIMIT) {
            String message = "超过 " + EXPORT_LIMIT + " 行，缩小时间范围或增加条件后重试";
            throw new ValidationException(
                    MeteringErrorCode.EXPORT_TOO_LARGE, message, List.of(new FieldViolation("from", message)));
        }
        StringBuilder csv = new StringBuilder(128 * (records.size() + 1));
        line(csv, COLUMNS.stream());
        for (UsageRecord record : records) {
            line(
                    csv,
                    Stream.of(
                            record.id(),
                            record.occurredAt().toString(),
                            record.kind().value(),
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
                            Long.toString(record.inputTokens()),
                            Long.toString(record.outputTokens()),
                            Long.toString(record.cacheReadTokens()),
                            Long.toString(record.cacheWriteTokens()),
                            Long.toString(record.reasoningTokens()),
                            record.quantity().toPlainString(),
                            record.priceBookId(),
                            record.priceBookVersion() == null
                                    ? null
                                    : record.priceBookVersion().toString(),
                            record.currency(),
                            plain(record.cost()),
                            JsonMappers.lenient().writeValueAsString(record.attributes())));
        }
        return csv.toString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static void line(StringBuilder csv, Stream<String> fields) {
        csv.append(String.join(",", fields.map(UsageRecords::field).toList())).append("\r\n");
    }

    /** 一个 CSV 字段：空值为空串；含逗号、双引号、换行的加双引号，内部双引号写两遍。 */
    static String field(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
