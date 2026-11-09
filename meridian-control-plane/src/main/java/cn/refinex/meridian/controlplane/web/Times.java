package cn.refinex.meridian.controlplane.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 管理 API 的时间转换：RFC 3339 UTC（架构设计「约定」）。生成的 DTO 用 {@link OffsetDateTime}。 */
public final class Times {

    private Times() {}

    /**
     * 转为 UTC 的 {@link OffsetDateTime}。
     *
     * @param instant 时间点；可为空
     * @return UTC 时间；输入为空时为空
     */
    public static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * 转为 {@link Instant}。
     *
     * @param time 带时区的时间；可为空
     * @return 时间点；输入为空时为空
     */
    public static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
