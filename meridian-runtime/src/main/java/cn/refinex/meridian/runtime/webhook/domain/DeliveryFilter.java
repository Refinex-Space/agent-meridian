package cn.refinex.meridian.runtime.webhook.domain;

/**
 * 投递记录列表的过滤条件，都可为空，条件之间是"且"。
 *
 * @param webhookId 只列出该 Webhook 的
 * @param sessionId 只列出该会话的
 * @param status 只列出该状态的
 */
public record DeliveryFilter(String webhookId, String sessionId, DeliveryStatus status) {}
