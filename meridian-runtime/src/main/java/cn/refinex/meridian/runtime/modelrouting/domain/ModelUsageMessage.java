package cn.refinex.meridian.runtime.modelrouting.domain;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelCallRecord;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次模型调用的用量记录，写入 outbox 的 {@code usage} 主题，由 control-plane 的消费者按价格表版本计价入账（架构设计「计量与预算」
 * 中的账本字段，T16 定稿消费格式）。输入、输出、缓存读、缓存写、推理 token 分别记；缓存读写与推理是输入或输出的子集。
 *
 * <p>消费方按 outbox 消息 ID 幂等（{@code meridian-persistence} 的约定），{@code request_id} 用于与事件、日志关联。
 *
 * @param kind 用量种类，模型 token 固定为 {@value #KIND_MODEL_TOKENS}
 * @param requestId 模型调用 ID
 * @param occurredAt 结算时间
 * @param tenantId 租户 ID
 * @param projectId 项目 ID；可为空
 * @param apiKeyId API Key ID；可为空
 * @param agentVersionId 智能体版本 ID；可为空
 * @param sessionId 会话 ID；可为空
 * @param threadId 线程 ID；可为空
 * @param turnId 轮次 ID；可为空
 * @param modelId 实际调用的模型 ID
 * @param providerAccountId 供应商账号 ID
 * @param upstreamModel 供应商侧的模型名
 * @param purpose 调用用途：{@code reasoning} 或 {@code compaction}
 * @param fallback 是否为降级到备用模型的调用
 * @param usageSource 用量来源：{@code provider} 或 {@code estimated}
 * @param inputTokens 输入 token
 * @param outputTokens 输出 token
 * @param cacheReadTokens 命中提示词缓存的输入 token
 * @param cacheWriteTokens 写入提示词缓存的输入 token
 * @param reasoningTokens 推理用的输出 token
 */
public record ModelUsageMessage(
        String kind,
        String requestId,
        Instant occurredAt,
        String tenantId,
        String projectId,
        String apiKeyId,
        String agentVersionId,
        String sessionId,
        String threadId,
        String turnId,
        String modelId,
        String providerAccountId,
        String upstreamModel,
        String purpose,
        boolean fallback,
        String usageSource,
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int cacheWriteTokens,
        int reasoningTokens) {

    /** 模型 token 用量的种类。 */
    public static final String KIND_MODEL_TOKENS = "model_tokens";

    /**
     * 创建用量记录。
     *
     * @throws NullPointerException 缺少租户、模型或用途等必填字段时
     */
    public ModelUsageMessage {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(usageSource, "usageSource");
    }

    /**
     * 由调用上下文与计量信息组装。
     *
     * @param context 调用的平台上下文
     * @param record 计量信息
     * @param occurredAt 结算时间
     * @return 用量记录
     */
    public static ModelUsageMessage of(PlatformContext context, ModelCallRecord record, Instant occurredAt) {
        return new ModelUsageMessage(
                KIND_MODEL_TOKENS,
                record.requestId(),
                occurredAt,
                context.tenantId(),
                context.projectId(),
                context.apiKeyId(),
                record.agentVersionId(),
                context.sessionId(),
                context.threadId(),
                context.turnId(),
                record.route().modelId(),
                record.route().providerAccountId(),
                record.route().upstreamModel(),
                record.purpose().value(),
                record.fallback(),
                record.source().value(),
                record.usage().inputTokens(),
                record.usage().outputTokens(),
                record.usage().cachedTokens(),
                record.usage().cacheCreationTokens(),
                record.usage().reasoningTokens());
    }

    /**
     * 计入预算的 token：输入加输出（缓存与推理是它们的子集）。
     *
     * @return token 数
     */
    public long billableTokens() {
        return (long) inputTokens + outputTokens;
    }
}
