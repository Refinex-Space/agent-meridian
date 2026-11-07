package cn.refinex.meridian.runtime.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 公开 API 的时间表示：RFC 3339 UTC（架构设计「API 设计」中的「约定」），生成的 DTO 用 {@link OffsetDateTime}。 */
public final class ApiTimes {

    private ApiTimes() {}

    /**
     * 转为 UTC 的 {@link OffsetDateTime}。
     *
     * @param instant 时间点；可为空
     * @return UTC 时间；输入为空时为空
     */
    public static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
