package cn.refinex.meridian.runtime.modelrouting.infra;

import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.runtime.modelrouting.domain.BudgetScope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 四级预算在 Redis 中的计数：预扣、只检查、结算与释放，Lua 原子执行（{@code budget_reserve.lua}、{@code budget_settle.lua}），见架构
 * 设计「计量与预算」中的实时扣减。计数以 token 计，含尚未结算的预扣；每小时与账本对账、以账本为准（T16）。
 *
 * <p>键带租户哈希标签，同一租户的各级计数与预扣记录落在同一个槽：已用计数 {@code budget:{租户}:used:{级别}:{范围 ID}}，
 * 预扣记录 {@code budget:{租户}:rsv:{预扣 ID}}。
 *
 * <p>方法会阻塞，响应式调用方经 {@code Blocking} 调用。线程安全：无状态。
 */
@Component
public class RedisBudgetLedger {

    private static final String SCRIPT_DIR = "cn/refinex/meridian/runtime/modelrouting/infra/";
    private static final RedisScript<Long> RESERVE = script("budget_reserve.lua");
    private static final RedisScript<Long> SETTLE = script("budget_settle.lua");

    private final StringRedisTemplate redis;

    /**
     * 创建预算计数。
     *
     * @param redis Redis 模板
     */
    public RedisBudgetLedger(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    /**
     * 预扣，或只检查各级是否容得下。
     *
     * @param tenantId 租户 ID（哈希标签）
     * @param scopes 各级范围与硬阈值
     * @param reservationId 预扣 ID
     * @param amount 预扣量
     * @param ttl 预扣记录的过期时间，超过后未结算的预扣留在计数里，等对账修正
     * @param take {@code true} 占用；{@code false} 只检查
     * @return 第一个不足的级别；都够时为空
     */
    public Optional<BudgetScope> reserve(
            String tenantId, List<BudgetScope> scopes, String reservationId, long amount, Duration ttl, boolean take) {
        List<String> args = new ArrayList<>(3 + scopes.size());
        args.add(Long.toString(amount));
        args.add(Long.toString(Math.max(1, ttl.toSeconds())));
        args.add(take ? "1" : "0");
        scopes.forEach(scope -> args.add(Long.toString(scope.hardLimit())));
        Long failed = redis.execute(RESERVE, keys(tenantId, scopes, reservationId), args.toArray());
        if (failed == null || failed == 0) {
            return Optional.empty();
        }
        return Optional.of(scopes.get(failed.intValue() - 1));
    }

    /**
     * 按实际用量结算预扣；释放即实际用量为 0 的结算。
     *
     * @param tenantId 租户 ID
     * @param scopes 预扣时的各级范围
     * @param reservationId 预扣 ID
     * @param actual 实际用量
     * @return 各级的调整量；预扣已结算或已过期时为空
     */
    public OptionalLong settle(String tenantId, List<BudgetScope> scopes, String reservationId, long actual) {
        Long delta = redis.execute(SETTLE, keys(tenantId, scopes, reservationId), Long.toString(actual));
        return delta == null ? OptionalLong.empty() : OptionalLong.of(delta);
    }

    /**
     * 一级预算的已用计数（含未结算的预扣），供对账与测试读取。
     *
     * @param tenantId 租户 ID
     * @param level 级别
     * @param scopeId 范围 ID
     * @return 已用 token
     */
    public long used(String tenantId, BudgetLevel level, String scopeId) {
        String value = redis.opsForValue().get(usedKey(tenantId, level, scopeId));
        return value == null ? 0 : Long.parseLong(value);
    }

    /**
     * 已用计数的键。
     *
     * @param tenantId 租户 ID
     * @param level 级别
     * @param scopeId 范围 ID
     * @return Redis 键
     */
    public static String usedKey(String tenantId, BudgetLevel level, String scopeId) {
        return "budget:{" + tenantId + "}:used:" + level.value() + ":" + scopeId;
    }

    private static List<String> keys(String tenantId, List<BudgetScope> scopes, String reservationId) {
        List<String> keys = new ArrayList<>(scopes.size() + 1);
        scopes.forEach(scope -> keys.add(usedKey(tenantId, scope.level(), scope.scopeId())));
        keys.add("budget:{" + tenantId + "}:rsv:" + reservationId);
        return keys;
    }

    private static RedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(SCRIPT_DIR + name));
        script.setResultType(Long.class);
        return script;
    }
}
