package cn.refinex.meridian.runtime.modelrouting.infra;

import cn.refinex.meridian.common.budget.BudgetKeys;
import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.runtime.modelrouting.domain.BudgetScope;
import java.time.Clock;
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
 * 设计「计量与预算」中的实时扣减。计数以 token 计，含尚未结算的预扣；control-plane 每小时以账本为准对账（ADR-0048 第 5 条）。
 *
 * <p>键见 {@link BudgetKeys}：租户、项目、API Key 的计数按自然月（UTC），会话不分周期。预扣 ID 带上预扣时的月份
 * （{@code ULID@yyyy-MM}），结算按同一组键修正，跨月的调用不会改到下个月的计数。预扣记录是哈希（预扣量与占用的计数键），预扣 ID 同时
 * 记进租户的集合，对账据此把未过期的预扣计入目标值。
 *
 * <p>方法会阻塞，响应式调用方经 {@code Blocking} 调用。线程安全：无状态。
 */
@Component
public class RedisBudgetLedger {

    /** 预扣 ID 中月份的分隔符。 */
    private static final char PERIOD_SEPARATOR = '@';

    private static final String SCRIPT_DIR = "cn/refinex/meridian/runtime/modelrouting/infra/";
    private static final RedisScript<Long> RESERVE = script("budget_reserve.lua");
    private static final RedisScript<Long> SETTLE = script("budget_settle.lua");

    private final StringRedisTemplate redis;
    private final Clock clock;

    /**
     * 创建预算计数。
     *
     * @param redis Redis 模板
     * @param clock 时钟，决定预扣所在的月份
     */
    public RedisBudgetLedger(StringRedisTemplate redis, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 新的预扣 ID：ULID 加上当前月份。
     *
     * @return 预扣 ID
     */
    public String newReservationId() {
        return Ids.newUlid() + PERIOD_SEPARATOR + BudgetKeys.month(clock.instant());
    }

    /**
     * 预扣，或只检查各级是否容得下。
     *
     * @param tenantId 租户 ID（哈希标签）
     * @param scopes 各级范围与硬阈值
     * @param reservationId 预扣 ID（{@link #newReservationId}）；只检查时可以是任意标识，按当前月份计
     * @param amount 预扣量
     * @param ttl 预扣记录的过期时间，超过后未结算的预扣留在计数里，等对账修正
     * @param take {@code true} 占用；{@code false} 只检查
     * @return 第一个不足的级别；都够时为空
     */
    public Optional<BudgetScope> reserve(
            String tenantId, List<BudgetScope> scopes, String reservationId, long amount, Duration ttl, boolean take) {
        List<String> args = new ArrayList<>(5 + scopes.size());
        args.add(Long.toString(amount));
        args.add(Long.toString(Math.max(1, ttl.toSeconds())));
        args.add(take ? "1" : "0");
        args.add(Long.toString(BudgetKeys.USED_TTL.toSeconds()));
        args.add(reservationId);
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
        Long delta = redis.execute(SETTLE, keys(tenantId, scopes, reservationId), Long.toString(actual), reservationId);
        return delta == null ? OptionalLong.empty() : OptionalLong.of(delta);
    }

    /**
     * 一级预算当前周期的已用计数（含未结算的预扣），供测试读取。
     *
     * @param tenantId 租户 ID
     * @param level 级别
     * @param scopeId 范围 ID
     * @return 已用 token
     */
    public long used(String tenantId, BudgetLevel level, String scopeId) {
        String value = redis.opsForValue()
                .get(BudgetKeys.used(tenantId, level, scopeId, BudgetKeys.period(level, clock.instant())));
        return value == null ? 0 : Long.parseLong(value);
    }

    private List<String> keys(String tenantId, List<BudgetScope> scopes, String reservationId) {
        String month = month(reservationId);
        List<String> keys = new ArrayList<>(scopes.size() + 2);
        scopes.forEach(scope -> keys.add(BudgetKeys.used(
                tenantId, scope.level(), scope.scopeId(), scope.level().monthly() ? month : BudgetKeys.TOTAL_PERIOD)));
        keys.add(BudgetKeys.reservation(tenantId, reservationId));
        keys.add(BudgetKeys.reservations(tenantId));
        return keys;
    }

    /** 预扣 ID 中的月份；没有时（只检查）取当前月份。 */
    private String month(String reservationId) {
        int at = reservationId.lastIndexOf(PERIOD_SEPARATOR);
        return at < 0 ? BudgetKeys.month(clock.instant()) : reservationId.substring(at + 1);
    }

    private static RedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(SCRIPT_DIR + name));
        script.setResultType(Long.class);
        return script;
    }
}
