package cn.refinex.meridian.runtime.modelrouting.domain;

import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.common.context.PlatformContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一次模型调用涉及的一级预算：级别、范围 ID 与硬阈值。
 *
 * @param level 级别；必填
 * @param scopeId 范围 ID（租户、项目、API Key 或会话的 ID）；必填
 * @param hardLimit 硬阈值，单位 token；负数表示不限
 */
public record BudgetScope(BudgetLevel level, String scopeId, long hardLimit) {

    /** 不限的硬阈值。 */
    public static final long UNLIMITED = -1;

    /**
     * 创建预算范围。
     *
     * @throws NullPointerException 缺少级别或范围 ID 时
     */
    public BudgetScope {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(scopeId, "scopeId");
    }

    /**
     * 调用上下文涉及的各级范围：租户必有，项目、API Key、会话在上下文中有值时才有，按级别顺序。
     *
     * @param context 平台上下文
     * @return 级别与范围 ID，硬阈值待填（{@link #UNLIMITED}）
     */
    public static List<BudgetScope> of(PlatformContext context) {
        List<BudgetScope> scopes = new ArrayList<>(4);
        scopes.add(new BudgetScope(BudgetLevel.TENANT, context.tenantId(), UNLIMITED));
        if (context.projectId() != null) {
            scopes.add(new BudgetScope(BudgetLevel.PROJECT, context.projectId(), UNLIMITED));
        }
        if (context.apiKeyId() != null) {
            scopes.add(new BudgetScope(BudgetLevel.API_KEY, context.apiKeyId(), UNLIMITED));
        }
        if (context.sessionId() != null) {
            scopes.add(new BudgetScope(BudgetLevel.SESSION, context.sessionId(), UNLIMITED));
        }
        return List.copyOf(scopes);
    }

    /**
     * 换上硬阈值。
     *
     * @param limit 硬阈值；负数表示不限
     * @return 新的范围
     */
    public BudgetScope withHardLimit(long limit) {
        return new BudgetScope(level, scopeId, limit);
    }
}
