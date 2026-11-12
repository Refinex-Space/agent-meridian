package cn.refinex.meridian.controlplane.budget.infra;

import cn.refinex.meridian.common.budget.BudgetKeys;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 预算在 Redis 中的读写（键见 {@link BudgetKeys}，ADR-0048）：control-plane 写限额哈希、读计数与预扣、按对账结果修正计数。
 * runtime 在同一组键上预扣与结算。
 *
 * <p>方法会阻塞。线程安全：无状态。
 */
@Component
public class RedisBudgetStore {

    private static final String SCRIPT_DIR = "cn/refinex/meridian/controlplane/budget/infra/";
    private static final RedisScript<Long> REPLACE_LIMITS = script("limits_replace.lua");
    private static final RedisScript<Long> ADJUST_USED = script("used_adjust.lua");

    private final StringRedisTemplate redis;

    /**
     * 创建存取入口。
     *
     * @param redis Redis 模板
     */
    public RedisBudgetStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    /**
     * 写入一级预算的硬阈值。
     *
     * @param tenantId 租户 ID
     * @param field 限额字段（{@link BudgetKeys#limitField}）
     * @param value 取值（{@link BudgetKeys#limitValue}）
     */
    public void putLimit(String tenantId, String field, String value) {
        redis.opsForHash().put(BudgetKeys.limits(tenantId), field, value);
    }

    /**
     * 删除一级预算的硬阈值：该范围回到同级的默认值，或按 0 处理。
     *
     * @param tenantId 租户 ID
     * @param field 限额字段
     */
    public void removeLimit(String tenantId, String field) {
        redis.opsForHash().delete(BudgetKeys.limits(tenantId), field);
    }

    /**
     * 整体替换一个租户的限额。
     *
     * @param tenantId 租户 ID
     * @param limits 字段与取值；为空表示没有任何预算
     */
    public void replaceLimits(String tenantId, Map<String, String> limits) {
        List<String> args = new ArrayList<>(limits.size() * 2);
        limits.forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });
        redis.execute(REPLACE_LIMITS, List.of(BudgetKeys.limits(tenantId)), args.toArray());
    }

    /**
     * 读取一个租户的全部限额。
     *
     * @param tenantId 租户 ID
     * @return 字段与取值
     */
    public Map<String, String> limits(String tenantId) {
        Map<String, String> limits = new HashMap<>();
        redis.opsForHash()
                .entries(BudgetKeys.limits(tenantId))
                .forEach((field, value) -> limits.put(String.valueOf(field), String.valueOf(value)));
        return limits;
    }

    /**
     * 读取已用计数。
     *
     * @param key 已用计数的键
     * @return 计数；键不存在时为 0
     */
    public long used(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    /**
     * 修正已用计数并刷新过期时间。
     *
     * @param key 已用计数的键
     * @param delta 修正量
     * @return 修正后的计数
     */
    public long adjust(String key, long delta) {
        Long value = redis.execute(
                ADJUST_USED, List.of(key), Long.toString(delta), Long.toString(BudgetKeys.USED_TTL.toSeconds()));
        return value == null ? 0 : value;
    }

    /**
     * 列出全部已用计数的键。
     *
     * @return 键
     */
    public Set<String> usedKeys() {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(BudgetKeys.usedPattern())
                .count(1000)
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

    /**
     * 一个租户未过期、未结算的预扣，按计数键汇总；顺带从集合中移除已过期的预扣 ID。
     *
     * @param tenantId 租户 ID
     * @return 计数键 → 预扣量之和
     */
    public Map<String, Long> inflight(String tenantId) {
        String setKey = BudgetKeys.reservations(tenantId);
        Set<String> ids = redis.opsForSet().members(setKey);
        Map<String, Long> inflight = new HashMap<>();
        if (ids == null) {
            return inflight;
        }
        for (String id : ids) {
            List<Object> values = redis.opsForHash()
                    .multiGet(
                            BudgetKeys.reservation(tenantId, id),
                            List.of(BudgetKeys.RESERVATION_AMOUNT, BudgetKeys.RESERVATION_KEYS));
            Object amount = values.get(0);
            Object keys = values.get(1);
            if (amount == null || keys == null) {
                redis.opsForSet().remove(setKey, id);
                continue;
            }
            long reserved = Long.parseLong(amount.toString());
            for (String key : keys.toString().split(" ")) {
                if (!key.isEmpty()) {
                    inflight.merge(key, reserved, Long::sum);
                }
            }
        }
        return inflight;
    }

    private static RedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(SCRIPT_DIR + name));
        script.setResultType(Long.class);
        return script;
    }
}
