package cn.refinex.meridian.runtime.webhook.domain;

import java.time.Instant;

/**
 * 投递记录列表的过滤条件，都可为空，条件之间是"且"；创建时间的范围左闭右开（时间范围 T19b，D72）。
 *
 * @param webhookId 只列出该 Webhook 的
 * @param sessionId 只列出该会话的
 * @param status 只列出该状态的
 * @param createdAfter 只列出创建时间不早于它的
 * @param createdBefore 只列出创建时间早于它的
 */
public record DeliveryFilter(
        String webhookId, String sessionId, DeliveryStatus status, Instant createdAfter, Instant createdBefore) {}
