package cn.refinex.meridian.runtime.webhook.domain;

import java.time.Instant;

/**
 * 一条投递记录（公开 API 的视图，不含请求体）。
 *
 * @param id 投递记录 ID（{@code whd_…}）
 * @param webhookId Webhook ID
 * @param sessionId 会话 ID
 * @param eventId 事件 ID
 * @param eventType 事件类型
 * @param status 状态
 * @param attempts 已尝试的次数
 * @param nextAttemptAt 下一次尝试的时间；不再尝试时为空
 * @param lastAttemptAt 最近一次尝试的时间；可为空
 * @param lastResponseStatus 最近一次收到的 HTTP 状态码；可为空
 * @param lastError 最近一次失败的原因；可为空
 * @param redeliveryOf 手动重投时为原记录 ID；可为空
 * @param createdAt 创建时间
 * @param completedAt 成功或最终失败的时间；可为空
 */
public record WebhookDelivery(
        String id,
        String webhookId,
        String sessionId,
        String eventId,
        String eventType,
        DeliveryStatus status,
        int attempts,
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        Integer lastResponseStatus,
        String lastError,
        String redeliveryOf,
        Instant createdAt,
        Instant completedAt) {}
