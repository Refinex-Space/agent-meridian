package cn.refinex.meridian.common.budget;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * 预算在 Redis 中的键（ADR-0048 第 1 条）。所有键带租户哈希标签 {@code {租户}}，同一租户的计数、限额与预扣落在同一个槽，Lua 脚本可以
 * 原子地操作它们。
 *
 * <ul>
 *   <li>已用计数 {@code budget:{租户}:used:{级别}:{范围}:{周期}}：字符串整数，单位 token，含尚未结算的预扣。周期是 UTC 的
 *       {@code yyyy-MM}（租户、项目、API Key），会话为 {@value #TOTAL_PERIOD}。
 *   <li>限额 {@code budget:{租户}:limits}：哈希，字段见 {@link #limitField}，取值为硬阈值，{@value #UNLIMITED} 表示不限；没有字段
 *       的级别按 0 处理（新租户默认零预算）。
 *   <li>预扣 {@code budget:{租户}:rsv:{预扣 ID}}（哈希：{@code amount} 与以空格分隔的计数键 {@code keys}）与预扣 ID 的集合
 *       {@code budget:{租户}:rsvs}，对账时把未过期的预扣计入目标值。
 * </ul>
 */
public final class BudgetKeys {

    /** 不分周期的计数（会话）的周期取值。 */
    public static final String TOTAL_PERIOD = "total";

    /** 限额哈希中"不限"的取值。 */
    public static final String UNLIMITED = "-1";

    /** 限额哈希中某级"所有范围的默认值"的范围部分。 */
    public static final String DEFAULT_SCOPE = "*";

    /** 已用计数的过期时间：每次预扣刷新，覆盖一个月的周期与对账的回看。 */
    public static final Duration USED_TTL = Duration.ofDays(62);

    /** 预扣记录哈希中的预扣量字段。 */
    public static final String RESERVATION_AMOUNT = "amount";

    /** 预扣记录哈希中的计数键字段（以空格分隔）。 */
    public static final String RESERVATION_KEYS = "keys";

    private static final String PREFIX = "budget:{";

    private BudgetKeys() {}

    /**
     * 某一时刻所在的计数周期。
     *
     * @param level 级别
     * @param at 时刻
     * @return 按月的级别为 UTC 的 {@code yyyy-MM}，会话为 {@value #TOTAL_PERIOD}
     */
    public static String period(BudgetLevel level, Instant at) {
        return level.monthly() ? month(at) : TOTAL_PERIOD;
    }

    /**
     * 某一时刻所在的月份周期。
     *
     * @param at 时刻
     * @return UTC 的 {@code yyyy-MM}
     */
    public static String month(Instant at) {
        return YearMonth.from(at.atOffset(ZoneOffset.UTC)).toString();
    }

    /**
     * 已用计数的键。
     *
     * @param tenantId 租户 ID
     * @param level 级别
     * @param scopeId 范围 ID
     * @param period 周期，见 {@link #period}
     * @return Redis 键
     */
    public static String used(String tenantId, BudgetLevel level, String scopeId, String period) {
        return PREFIX + tenantId + "}:used:" + level.value() + ":" + scopeId + ":" + period;
    }

    /**
     * 匹配全部已用计数键的 {@code SCAN} 模式。
     *
     * @return 模式
     */
    public static String usedPattern() {
        return "budget:*:used:*";
    }

    /**
     * 解析已用计数的键。
     *
     * @param key Redis 键
     * @return 租户、级别、范围与周期；不是已用计数的键时为空
     */
    public static Optional<UsedKey> parseUsed(String key) {
        if (!key.startsWith(PREFIX)) {
            return Optional.empty();
        }
        int close = key.indexOf("}:used:");
        if (close < 0) {
            return Optional.empty();
        }
        String tenantId = key.substring(PREFIX.length(), close);
        String[] parts = key.substring(close + "}:used:".length()).split(":", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new UsedKey(tenantId, BudgetLevel.of(parts[0]), parts[1], parts[2]));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 限额哈希的键。
     *
     * @param tenantId 租户 ID
     * @return Redis 键
     */
    public static String limits(String tenantId) {
        return PREFIX + tenantId + "}:limits";
    }

    /**
     * 限额哈希中一级预算的字段：租户级为 {@code tenant}，其余为 {@code 级别:范围 ID}，范围为空表示该级所有范围的默认值
     * {@code 级别:*}。
     *
     * @param level 级别
     * @param scopeId 范围 ID；为空表示默认值。租户级忽略
     * @return 字段名
     */
    public static String limitField(BudgetLevel level, String scopeId) {
        if (level == BudgetLevel.TENANT) {
            return level.value();
        }
        return level.value() + ":" + (scopeId == null ? DEFAULT_SCOPE : scopeId);
    }

    /**
     * 硬阈值在限额哈希中的取值。
     *
     * @param hardLimit 硬阈值；为空表示不限
     * @return 字段值
     */
    public static String limitValue(Long hardLimit) {
        return hardLimit == null ? UNLIMITED : Long.toString(hardLimit);
    }

    /**
     * 预扣记录的键。
     *
     * @param tenantId 租户 ID
     * @param reservationId 预扣 ID
     * @return Redis 键
     */
    public static String reservation(String tenantId, String reservationId) {
        return PREFIX + tenantId + "}:rsv:" + reservationId;
    }

    /**
     * 租户的预扣 ID 集合的键。
     *
     * @param tenantId 租户 ID
     * @return Redis 键
     */
    public static String reservations(String tenantId) {
        return PREFIX + tenantId + "}:rsvs";
    }

    /**
     * 解析后的已用计数键。
     *
     * @param tenantId 租户 ID
     * @param level 级别
     * @param scopeId 范围 ID
     * @param period 周期
     */
    public record UsedKey(String tenantId, BudgetLevel level, String scopeId, String period) {

        /**
         * 创建解析结果。
         *
         * @throws NullPointerException 缺少字段时
         */
        public UsedKey {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(period, "period");
        }

        /**
         * 还原为 Redis 键。
         *
         * @return Redis 键
         */
        public String key() {
            return used(tenantId, level, scopeId, period);
        }
    }
}
