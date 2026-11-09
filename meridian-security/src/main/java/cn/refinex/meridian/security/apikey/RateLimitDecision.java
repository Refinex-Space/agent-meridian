package cn.refinex.meridian.security.apikey;

/**
 * 一次限流判定的结果，同时给出 {@code RateLimit-*} 与 {@code Retry-After} 的取值（ADR-0042）。
 *
 * @param allowed 是否放行
 * @param limit 上限：令牌桶的容量，对应 {@code RateLimit-Limit}
 * @param remaining 本次之后剩余的整数令牌，对应 {@code RateLimit-Remaining}，不小于 0
 * @param resetSeconds 令牌桶补满所需的秒数（向上取整），对应 {@code RateLimit-Reset}
 * @param retryAfterSeconds 拒绝时得到一个令牌所需的秒数（向上取整，至少 1），对应 {@code Retry-After}；放行时为 0
 */
public record RateLimitDecision(
        boolean allowed, int limit, long remaining, long resetSeconds, long retryAfterSeconds) {}
