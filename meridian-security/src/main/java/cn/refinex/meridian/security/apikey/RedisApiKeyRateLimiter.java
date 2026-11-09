package cn.refinex.meridian.security.apikey;

import java.time.Clock;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 令牌桶实现的 {@link ApiKeyRateLimiter}（ADR-0042）：键 {@code ratelimit:apikey:{key_id}}，一次 Lua 脚本完成补充、扣减与
 * 剩余量计算，多节点共享同一个桶。时间取注入的 {@link Clock}（编码规范「时钟」），节点之间的时间倒退不补充令牌。
 *
 * <p>阻塞调用，WebFlux 中经 {@code Blocking} 执行。线程安全：无状态。
 */
public final class RedisApiKeyRateLimiter implements ApiKeyRateLimiter {

    private static final RedisScript<List> SCRIPT = script();

    private final StringRedisTemplate redis;
    private final Clock clock;

    /**
     * 创建限流器。
     *
     * @param redis Redis 模板
     * @param clock 时钟
     */
    public RedisApiKeyRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * 令牌桶的键。花括号是 Redis Cluster 的哈希标签，同一个 Key 的桶落在同一个槽。
     *
     * @param apiKeyId API Key ID
     * @return Redis 键
     */
    public static String key(String apiKeyId) {
        return "ratelimit:apikey:{" + apiKeyId + "}";
    }

    @Override
    public RateLimitDecision consume(String apiKeyId, int limitPerMinute) {
        List<?> result = redis.execute(
                SCRIPT, List.of(key(apiKeyId)), Long.toString(clock.millis()), Integer.toString(limitPerMinute));
        boolean allowed = number(result, 0) == 1;
        long remaining = Math.max(0, number(result, 1));
        long resetSeconds = ceilSeconds(number(result, 2));
        long retryAfterSeconds = allowed ? 0 : Math.max(1, ceilSeconds(number(result, 3)));
        return new RateLimitDecision(allowed, limitPerMinute, remaining, resetSeconds, retryAfterSeconds);
    }

    private static long number(List<?> result, int index) {
        return ((Number) result.get(index)).longValue();
    }

    private static long ceilSeconds(long millis) {
        return (millis + 999) / 1000;
    }

    @SuppressWarnings("rawtypes") // 脚本返回 Lua 表，Spring Data Redis 以原始类型 List 表示
    private static RedisScript<List> script() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("cn/refinex/meridian/security/apikey/api_key_rate_limit.lua"));
        script.setResultType(List.class);
        return script;
    }
}
