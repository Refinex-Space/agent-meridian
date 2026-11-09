package cn.refinex.meridian.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.testsupport.MeridianContainers;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** {@link RedisApiKeyRateLimiter} 的令牌桶脚本（ADR-0042）：容量、按时间补充、剩余量与重试时间，在真实 Redis 上执行。 */
class RedisApiKeyRateLimiterIT {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-11-09T00:00:00Z"));

    @BeforeAll
    static void connect() {
        var container = MeridianContainers.redis();
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @Test
    void theBucketHoldsTheLimitAndRefillsPerMinute() {
        RedisApiKeyRateLimiter limiter = new RedisApiKeyRateLimiter(redis, clock);
        String key = Ids.newId(IdPrefix.API_KEY);

        for (int i = 1; i <= 6; i++) {
            RateLimitDecision decision = limiter.consume(key, 6);
            assertThat(decision.allowed()).as("第 %d 次", i).isTrue();
            assertThat(decision.remaining()).isEqualTo(6 - i);
            // 每分钟补 6 个，每个 10 秒：缺 i 个令牌，补满要 10·i 秒
            assertThat(decision.resetSeconds()).isEqualTo(10L * i);
        }
        RateLimitDecision rejected = limiter.consume(key, 6);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(10);

        clock.advance(Duration.ofSeconds(4));
        assertThat(limiter.consume(key, 6).retryAfterSeconds()).isEqualTo(6);

        clock.advance(Duration.ofSeconds(6));
        assertThat(limiter.consume(key, 6).allowed()).isTrue();
        assertThat(limiter.consume(key, 6).allowed()).isFalse();
    }

    @Test
    void bucketsAreIndependentPerKeyAndSharedAcrossLimiters() {
        String key = Ids.newId(IdPrefix.API_KEY);
        RedisApiKeyRateLimiter nodeA = new RedisApiKeyRateLimiter(redis, clock);
        RedisApiKeyRateLimiter nodeB = new RedisApiKeyRateLimiter(redis, clock);

        assertThat(nodeA.consume(key, 2).allowed()).isTrue();
        assertThat(nodeB.consume(key, 2).allowed()).isTrue();
        assertThat(nodeA.consume(key, 2).allowed()).isFalse();
        assertThat(nodeB.consume(Ids.newId(IdPrefix.API_KEY), 2).allowed()).isTrue();
    }

    @Test
    void clockSkewBetweenNodesDoesNotRefill() {
        String key = Ids.newId(IdPrefix.API_KEY);
        MutableClock behind = MutableClock.at(clock.instant().minusSeconds(30));

        assertThat(new RedisApiKeyRateLimiter(redis, clock).consume(key, 1).allowed())
                .isTrue();
        assertThat(new RedisApiKeyRateLimiter(redis, behind).consume(key, 1).allowed())
                .isFalse();
    }
}
