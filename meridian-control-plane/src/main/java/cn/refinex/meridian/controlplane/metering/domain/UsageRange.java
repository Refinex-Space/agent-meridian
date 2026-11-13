package cn.refinex.meridian.controlplane.metering.domain;

import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 用量查询的时间范围（报表与明细共用，ADR-0048）：左闭右开，默认从当前 UTC 月份的第一天到当前时间，最长 {@link #MAX_RANGE}。
 *
 * @param from 起始时间（含）
 * @param to 结束时间（不含）
 */
public record UsageRange(Instant from, Instant to) {

    /** 一次查询的最长时间范围。 */
    public static final Duration MAX_RANGE = Duration.ofDays(366);

    /**
     * 按请求的参数确定范围。
     *
     * @param from 起始时间；可为空，取当前 UTC 月份的第一天
     * @param to 结束时间；可为空，取当前时间
     * @param now 当前时间
     * @return 范围
     * @throws ValidationException 起始不早于结束、或超过 {@link #MAX_RANGE} 时（{@code usage.invalid_query}）
     */
    public static UsageRange resolve(Instant from, Instant to, Instant now) {
        Instant end = to == null ? now : to;
        Instant start = from == null
                ? LocalDate.ofInstant(now, ZoneOffset.UTC)
                        .withDayOfMonth(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                : from;
        if (!start.isBefore(end)) {
            throw invalid("from", "起始时间必须早于结束时间");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw invalid("to", "时间范围不能超过 366 天");
        }
        return new UsageRange(start, end);
    }

    private static ValidationException invalid(String field, String message) {
        return new ValidationException(
                MeteringErrorCode.INVALID_QUERY, message, List.of(new FieldViolation(field, message)));
    }
}
