package cn.refinex.meridian.runtime.modelrouting.infra;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.runtime.modelrouting.domain.RateLimit;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 令牌桶 Lua 脚本（{@code rate_limit_acquire.lua}、{@code rate_limit_refund.lua}）的行为，Testcontainers 的 Redis。 */
class RedisRateLimiterIT {

    private static final Instant T0 = Instant.parse("2026-11-08T08:00:00Z");

    private final StringRedisTemplate redis = RuntimeTestRedis.template();
    private final RedisRateLimiter limiter = new RedisRateLimiter(redis);
    private String key;

    @BeforeEach
    void freshBucket() {
        key = RedisRateLimiter.key("prv_" + Ids.newUlid(), "qwen-plus");
    }

    @Test
    void requestsBeyondRpmWaitUntilTheBucketRefills() {
        RateLimit limit = new RateLimit(2, 0);

        assertThat(limiter.acquire(key, limit, 100, T0, true)).isZero();
        assertThat(limiter.acquire(key, limit, 100, T0, true)).isZero();
        assertThat(limiter.acquire(key, limit, 100, T0, true))
                .as("每分钟 2 次：补满一次请求要 30 秒")
                .isEqualTo(30_000);
        assertThat(limiter.acquire(key, limit, 100, T0.plusSeconds(20), true)).isEqualTo(10_000);
        assertThat(limiter.acquire(key, limit, 100, T0.plusSeconds(30), true)).isZero();
    }

    @Test
    void tokensBeyondTpmWaitInProportionToTheShortfall() {
        RateLimit limit = new RateLimit(0, 1_000);

        assertThat(limiter.acquire(key, limit, 600, T0, true)).isZero();
        assertThat(limiter.acquire(key, limit, 600, T0, true))
                .as("还剩 400，缺 200，按每分钟 1000 补充要 12 秒")
                .isEqualTo(12_000);
    }

    @Test
    void aRequestLargerThanTheBucketPassesWhenTheBucketIsFull() {
        RateLimit limit = new RateLimit(0, 1_000);

        assertThat(limiter.acquire(key, limit, 5_000, T0, true))
                .as("按桶容量计，满桶可以通过")
                .isZero();
        assertThat(limiter.acquire(key, limit, 1, T0, true)).isPositive();
    }

    @Test
    void checkingDoesNotConsume() {
        RateLimit limit = new RateLimit(1, 0);

        assertThat(limiter.acquire(key, limit, 1, T0, false)).isZero();
        assertThat(limiter.acquire(key, limit, 1, T0, false)).isZero();
        assertThat(limiter.acquire(key, limit, 1, T0, true)).isZero();
        assertThat(limiter.acquire(key, limit, 1, T0, false)).isEqualTo(60_000);
    }

    @Test
    void refundReturnsUnusedTokensUpToTheCapacity() {
        RateLimit limit = new RateLimit(0, 1_000);
        limiter.acquire(key, limit, 800, T0, true);

        limiter.refund(key, limit, 500, T0);

        assertThat(limiter.acquire(key, limit, 700, T0, true))
                .as("200 加退回的 500")
                .isZero();
        limiter.refund(key, limit, 5_000, T0);
        assertThat(limiter.acquire(key, limit, 1_000, T0, true)).as("退回不超过容量").isZero();
        assertThat(limiter.acquire(key, limit, 1, T0, true)).isPositive();
    }

    @Test
    void unlimitedBucketsNeverWaitAndStoreNothing() {
        assertThat(limiter.acquire(key, RateLimit.UNLIMITED, 1_000_000, T0, true))
                .isZero();
        assertThat(redis.hasKey(key)).isFalse();
    }
}
