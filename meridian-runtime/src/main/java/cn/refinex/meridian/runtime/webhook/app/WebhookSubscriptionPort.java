package cn.refinex.meridian.runtime.webhook.app;

import cn.refinex.meridian.runtime.webhook.domain.WebhookSubscription;
import java.util.List;

/**
 * 项目的 Webhook 订阅的来源（ADR-0049）：生产经 control-plane 内部接口 {@code GET /internal/v1/projects/{id}/webhooks} 读取并缓存；
 * 没有配置 control-plane 时没有任何订阅。实现可以阻塞，必须线程安全。
 */
public interface WebhookSubscriptionPort {

    /**
     * 项目的全部 Webhook（含停用的）。
     *
     * @param tenantId 租户 ID，用于淘汰与校验归属
     * @param projectId 项目 ID
     * @return Webhook；项目没有 Webhook 时为空列表
     * @throws cn.refinex.meridian.runtime.session.domain.ConfigUnavailableException 暂时读不到时，调用方稍后重试
     */
    List<WebhookSubscription> forProject(String tenantId, String projectId);

    /**
     * 淘汰租户的缓存（{@code config.invalidate} 的 {@code webhook}）。
     *
     * @param tenantId 租户 ID
     */
    default void evictTenant(String tenantId) {}
}
