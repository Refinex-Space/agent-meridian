package cn.refinex.meridian.controlplane.budget.domain;

import cn.refinex.meridian.common.budget.BudgetKeys;
import cn.refinex.meridian.common.budget.BudgetLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一条用量记录中计入预算的部分（ADR-0048 第 1 条）：模型调用的输入加输出 token，落在它涉及的各级范围与周期上。
 *
 * @param tenantId 租户 ID
 * @param projectId 项目 ID；可为空
 * @param apiKeyId API Key ID；可为空
 * @param sessionId 会话 ID；可为空
 * @param occurredAt 用量发生时间，决定按月的周期
 * @param tokens 计入预算的 token，大于 0
 */
public record BudgetCharge(
        String tenantId, String projectId, String apiKeyId, String sessionId, Instant occurredAt, long tokens) {

    /**
     * 创建计费项。
     *
     * @throws NullPointerException 缺少租户或时间时
     */
    public BudgetCharge {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /**
     * 涉及的各级范围与周期：租户必有，项目、API Key、会话有值时才有，与 runtime 预扣时的范围一致。
     *
     * @return 范围
     */
    public List<Scope> scopes() {
        List<Scope> scopes = new ArrayList<>(4);
        scopes.add(scope(BudgetLevel.TENANT, tenantId));
        if (projectId != null) {
            scopes.add(scope(BudgetLevel.PROJECT, projectId));
        }
        if (apiKeyId != null) {
            scopes.add(scope(BudgetLevel.API_KEY, apiKeyId));
        }
        if (sessionId != null) {
            scopes.add(scope(BudgetLevel.SESSION, sessionId));
        }
        return scopes;
    }

    private Scope scope(BudgetLevel level, String scopeId) {
        return new Scope(level, scopeId, BudgetKeys.period(level, occurredAt));
    }

    /**
     * 一级范围与周期。
     *
     * @param level 级别
     * @param scopeId 范围 ID（租户级为租户 ID）
     * @param period 周期
     */
    public record Scope(BudgetLevel level, String scopeId, String period) {}
}
