package cn.refinex.meridian.security.apikey;

/**
 * 按 API Key 的限流（架构设计「约定」中的限流，ADR-0042）：每个经过认证的公开 API 请求消耗一个令牌。实现可以阻塞，必须线程安全。
 */
public interface ApiKeyRateLimiter {

    /**
     * 为一个请求消耗一个令牌。
     *
     * @param apiKeyId API Key ID
     * @param limitPerMinute 每分钟上限（令牌桶的容量与每分钟补充量）
     * @return 判定结果与响应头的取值
     */
    RateLimitDecision consume(String apiKeyId, int limitPerMinute);
}
