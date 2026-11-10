package cn.refinex.meridian.security.apikey;

/**
 * 公开 API 的限流（架构设计「约定」中的限流，ADR-0042）：每个经过认证的请求消耗一个令牌，API Key 按 Key 计，控制台用户按用户计
 * （ADR-0044）。实现可以阻塞，必须线程安全。
 */
public interface ApiKeyRateLimiter {

    /**
     * 为一个请求消耗一个令牌。
     *
     * @param bucket 桶名，见 {@link PublicApiPrincipal#rateLimitBucket()}
     * @param limitPerMinute 每分钟上限（令牌桶的容量与每分钟补充量）
     * @return 判定结果与响应头的取值
     */
    RateLimitDecision consume(String bucket, int limitPerMinute);
}
