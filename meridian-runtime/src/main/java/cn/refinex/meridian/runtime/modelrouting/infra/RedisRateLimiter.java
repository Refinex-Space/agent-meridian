package cn.refinex.meridian.runtime.modelrouting.infra;

import cn.refinex.meridian.runtime.modelrouting.domain.RateLimit;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * "供应商账号加模型"的 RPM 与 TPM 令牌桶，Lua 原子地检查与扣减（{@code rate_limit_acquire.lua}、{@code rate_limit_refund.lua}），
 * 见架构设计「模型调用」第 2 步。等待与超时由调用方循环处理。
 *
 * <p>键为 {@code ratelimit:{供应商账号}:{供应商侧模型名}}，时间取调用方传入的 {@code Clock}（ADR-0027：各节点 NTP 同步）。
 *
 * <p>方法会阻塞，响应式调用方经 {@code Blocking} 调用（不在 Netty 事件循环上发 Redis 命令）。线程安全：无状态。
 */
@Component
public class RedisRateLimiter {

    private static final String SCRIPT_DIR = "cn/refinex/meridian/runtime/modelrouting/infra/";
    private static final RedisScript<Long> ACQUIRE = script("rate_limit_acquire.lua");
    private static final RedisScript<Long> REFUND = script("rate_limit_refund.lua");

    private final StringRedisTemplate redis;

    /**
     * 创建令牌桶。
     *
     * @param redis Redis 模板
     */
    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    /**
     * 令牌桶的键。
     *
     * @param providerAccountId 供应商账号 ID
     * @param upstreamModel 供应商侧模型名
     * @return Redis 键
     */
    public static String key(String providerAccountId, String upstreamModel) {
        return "ratelimit:{" + providerAccountId + "}:" + upstreamModel;
    }

    /**
     * 尝试拿一次请求与 {@code tokens} 个 token 的配额。
     *
     * @param key 令牌桶的键
     * @param limit 限流参数
     * @param tokens 本次的 token 估算
     * @param now 当前时间
     * @param take 拿到时是否扣减；{@code false} 只检查
     * @return 0 表示可以发出请求；否则为还需等待的毫秒数
     */
    public long acquire(String key, RateLimit limit, int tokens, Instant now, boolean take) {
        Long wait = redis.execute(
                ACQUIRE,
                List.of(key),
                Long.toString(now.toEpochMilli()),
                Integer.toString(limit.rpm()),
                Integer.toString(limit.tpm()),
                Integer.toString(tokens),
                take ? "1" : "0");
        return wait == null ? 0 : wait;
    }

    /**
     * 把多扣的 token 退回 TPM 桶。
     *
     * @param key 令牌桶的键
     * @param limit 限流参数
     * @param tokens 退回的 token 数，不大于 0 时不操作
     * @param now 当前时间
     */
    public void refund(String key, RateLimit limit, int tokens, Instant now) {
        if (tokens <= 0 || limit.tpm() == 0) {
            return;
        }
        redis.execute(
                REFUND,
                List.of(key),
                Long.toString(now.toEpochMilli()),
                Integer.toString(limit.tpm()),
                Integer.toString(tokens));
    }

    private static RedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(SCRIPT_DIR + name));
        script.setResultType(Long.class);
        return script;
    }
}
