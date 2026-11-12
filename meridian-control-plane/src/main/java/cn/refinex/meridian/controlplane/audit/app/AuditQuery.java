package cn.refinex.meridian.controlplane.audit.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.audit.domain.AuditEntry;
import cn.refinex.meridian.controlplane.audit.domain.AuditFilter;
import cn.refinex.meridian.controlplane.audit.infra.AuditLogRepository;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** 审计查询（ADR-0048 第 6 条）：时间范围左闭右开，默认最近 {@link #DEFAULT_RANGE}，条件之间是"且"。 */
@Service
public class AuditQuery {

    /** 不给出起始时间时的回看范围。 */
    static final Duration DEFAULT_RANGE = Duration.ofDays(30);

    private final AuditLogRepository repository;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param repository 审计仓储
     * @param clock 时钟
     */
    public AuditQuery(AuditLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 查询审计日志。
     *
     * @param tenantId 租户 ID
     * @param filter 条件，时间可为空
     * @param page 分页参数
     * @return 一页审计，从新到旧
     */
    public PageResult<AuditEntry> search(String tenantId, AuditFilter filter, PageRequest page) {
        Instant to = filter.to() == null ? clock.instant() : filter.to();
        Instant from = filter.from() == null ? to.minus(DEFAULT_RANGE) : filter.from();
        if (!from.isBefore(to)) {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "起始时间必须早于结束时间",
                    List.of(new FieldViolation("from", "起始时间必须早于结束时间")));
        }
        AuditFilter bounded = filter.between(from, to);
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> PageResult.of(repository.search(bounded, page), page, AuditLogRepository::cursorOf));
    }
}
