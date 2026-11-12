package cn.refinex.meridian.controlplane.metering.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 账本中的一条用量记录（{@code cp.usage_records}，ADR-0048 第 3 条）。缓存读写是输入的子集，推理是输出的子集。
 *
 * @param id outbox 消息 ID
 * @param occurredAt 用量发生时间
 * @param kind 计量种类
 * @param tenantId 租户 ID
 * @param projectId 项目 ID；可为空
 * @param apiKeyId API Key ID；可为空
 * @param agentId 智能体 ID；入账时按智能体版本查得，可为空
 * @param agentVersionId 智能体版本 ID；可为空
 * @param sessionId 会话 ID；可为空
 * @param threadId 线程 ID；可为空
 * @param turnId 轮次 ID；可为空
 * @param requestId 模型调用 ID；可为空
 * @param modelId 模型 ID；可为空
 * @param providerAccountId 供应商账号 ID；可为空
 * @param upstreamModel 供应商侧的模型名；可为空
 * @param purpose 调用用途；可为空
 * @param fallback 是否为降级调用
 * @param usageSource 用量来源；可为空
 * @param inputTokens 输入 token
 * @param outputTokens 输出 token
 * @param cacheReadTokens 缓存读 token
 * @param cacheWriteTokens 缓存写 token
 * @param reasoningTokens 推理 token
 * @param quantity 计量数量：token 种类为输入加输出，沙箱为 vCPU 秒，搜索为次数
 * @param priceBookId 价格表；可为空
 * @param priceBookVersion 价格表版本；可为空
 * @param currency 币种；没有计价时为空
 * @param cost 成本；没有计价时为空
 * @param attributes 种类特有的信息
 */
public record UsageRecord(
        String id,
        Instant occurredAt,
        UsageKind kind,
        String tenantId,
        String projectId,
        String apiKeyId,
        String agentId,
        String agentVersionId,
        String sessionId,
        String threadId,
        String turnId,
        String requestId,
        String modelId,
        String providerAccountId,
        String upstreamModel,
        String purpose,
        boolean fallback,
        String usageSource,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long reasoningTokens,
        BigDecimal quantity,
        String priceBookId,
        Integer priceBookVersion,
        String currency,
        BigDecimal cost,
        Map<String, Object> attributes) {

    /**
     * 创建记录。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException token 或数量为负时
     */
    public UsageRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(quantity, "quantity");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        if (inputTokens < 0
                || outputTokens < 0
                || cacheReadTokens < 0
                || cacheWriteTokens < 0
                || reasoningTokens < 0
                || quantity.signum() < 0) {
            throw new IllegalArgumentException("用量不能为负：" + id);
        }
    }

    /**
     * 计入预算的 token：模型 token 种类的输入加输出，其余种类为 0（ADR-0048 第 1 条）。
     *
     * @return token 数
     */
    public long billableTokens() {
        return kind.tokens() ? inputTokens + outputTokens : 0;
    }

    /**
     * 补上智能体与成本。
     *
     * @param agent 智能体 ID；可为空
     * @param currencyCode 币种；可为空
     * @param amount 成本；可为空
     * @return 新的记录
     */
    public UsageRecord priced(String agent, String currencyCode, BigDecimal amount) {
        return new UsageRecord(
                id,
                occurredAt,
                kind,
                tenantId,
                projectId,
                apiKeyId,
                agent,
                agentVersionId,
                sessionId,
                threadId,
                turnId,
                requestId,
                modelId,
                providerAccountId,
                upstreamModel,
                purpose,
                fallback,
                usageSource,
                inputTokens,
                outputTokens,
                cacheReadTokens,
                cacheWriteTokens,
                reasoningTokens,
                quantity,
                priceBookId,
                priceBookVersion,
                currencyCode,
                amount,
                attributes);
    }
}
