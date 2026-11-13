package cn.refinex.meridian.runtime.session.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;

/**
 * 会话统计的时间窗（{@code GET /v1/session-stats}，ADR-0053）：按 UTC 整点对齐，含当前小时，与失败率的小时汇总表的粒度一致。
 */
public enum StatsWindow {
    /** 当前小时。 */
    ONE_HOUR("1h", 1),
    /** 最近 24 个小时（含当前小时）。 */
    ONE_DAY("24h", 24),
    /** 最近 7 天（168 个小时，含当前小时）。 */
    SEVEN_DAYS("7d", 168);

    /** 汇总表的保留期：比最长的时间窗多一天，清理不会删掉时间窗内的小时。 */
    public static final Duration RETENTION = Duration.ofDays(8);

    private final String value;
    private final int hours;

    StatsWindow(String value, int hours) {
        this.value = value;
        this.hours = hours;
    }

    /**
     * 返回 API 中的取值。
     *
     * @return 例如 {@code 24h}
     */
    public String value() {
        return value;
    }

    /**
     * 时间窗的起点：当前小时的整点往前推 {@code 小时数 - 1} 个小时。
     *
     * @param now 当前时间
     * @return 起点（UTC 整点）
     */
    public Instant start(Instant now) {
        return now.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(hours - 1L));
    }

    /**
     * 按取值查找。
     *
     * @param value 取值
     * @return 时间窗；不认识时为空
     */
    public static Optional<StatsWindow> of(String value) {
        return Arrays.stream(values()).filter(w -> w.value.equals(value)).findFirst();
    }
}
