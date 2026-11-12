package cn.refinex.meridian.runtime.modelrouting.infra;

import cn.refinex.meridian.common.budget.BudgetKeys;
import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.runtime.modelrouting.app.BudgetLimitPort;
import cn.refinex.meridian.runtime.modelrouting.domain.BudgetScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 读 control-plane 同步到 Redis 的预算限额（{@link BudgetLimitPort} 的生产实现，ADR-0048 第 2 条）：每次调用一次 {@code HMGET} 读出
 * 四级的具体范围与默认值，具体范围优先；都没有时为 0，新租户在管理员设置预算之前不能调用模型。
 *
 * <p>方法会阻塞。线程安全：无状态。
 */
public final class RedisBudgetLimits implements BudgetLimitPort {

    private final StringRedisTemplate redis;

    /**
     * 创建限额来源。
     *
     * @param redis Redis 模板
     */
    public RedisBudgetLimits(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public List<BudgetScope> withLimits(String tenantId, List<BudgetScope> scopes) {
        List<Object> fields = new ArrayList<>(scopes.size() * 2);
        for (BudgetScope scope : scopes) {
            fields.add(BudgetKeys.limitField(scope.level(), scope.scopeId()));
            fields.add(BudgetKeys.limitField(scope.level(), null));
        }
        List<Object> values = redis.opsForHash().multiGet(BudgetKeys.limits(tenantId), fields);
        List<BudgetScope> limited = new ArrayList<>(scopes.size());
        for (int i = 0; i < scopes.size(); i++) {
            BudgetScope scope = scopes.get(i);
            Object specific = values.get(i * 2);
            Object fallback = scope.level() == BudgetLevel.TENANT ? null : values.get(i * 2 + 1);
            Object value = specific != null ? specific : fallback;
            limited.add(scope.withHardLimit(value == null ? 0 : Long.parseLong(value.toString())));
        }
        return List.copyOf(limited);
    }
}
