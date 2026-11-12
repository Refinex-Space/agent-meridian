package cn.refinex.meridian.runtime.webhook.infra;

import cn.refinex.meridian.api.internal.model.InternalWebhook;
import cn.refinex.meridian.api.internal.model.InternalWebhookList;
import cn.refinex.meridian.runtime.session.domain.ConfigUnavailableException;
import cn.refinex.meridian.runtime.webhook.app.WebhookSubscriptionPort;
import cn.refinex.meridian.runtime.webhook.domain.WebhookSubscription;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 经 control-plane 内部接口 {@code GET /internal/v1/projects/{id}/webhooks} 读取项目的订阅（ADR-0049），按项目缓存；
 * {@code config.invalidate} 的 {@code webhook} 淘汰该租户的全部项目。项目已删除（404）时按没有订阅处理。
 *
 * <p>线程安全：Caffeine 按键计算。方法会阻塞（HTTP）。
 */
public class ControlPlaneWebhookSubscriptions implements WebhookSubscriptionPort {

    private final RestClient client;
    private final Cache<Key, List<WebhookSubscription>> cache;

    /**
     * 创建来源。
     *
     * @param client 指向 control-plane 的服务间调用客户端
     * @param ttl 缓存时间
     */
    public ControlPlaneWebhookSubscriptions(RestClient client, Duration ttl) {
        this.client = Objects.requireNonNull(client, "client");
        this.cache =
                Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
    }

    @Override
    public List<WebhookSubscription> forProject(String tenantId, String projectId) {
        return cache.get(new Key(tenantId, projectId), key -> load(key));
    }

    @Override
    public void evictTenant(String tenantId) {
        cache.asMap().keySet().removeIf(key -> key.tenantId().equals(tenantId));
    }

    private List<WebhookSubscription> load(Key key) {
        InternalWebhookList list;
        try {
            list = client.get()
                    .uri("/internal/v1/projects/{project_id}/webhooks", key.projectId())
                    .retrieve()
                    .body(InternalWebhookList.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return List.of();
            }
            throw new ConfigUnavailableException("Webhook 订阅暂时无法读取：项目 " + key.projectId(), e);
        } catch (RestClientException e) {
            throw new ConfigUnavailableException("Webhook 订阅暂时无法读取：项目 " + key.projectId(), e);
        }
        if (list == null) {
            throw new ConfigUnavailableException("Webhook 订阅暂时无法读取：项目 " + key.projectId(), null);
        }
        return list.getData().stream()
                // 归属以租户为准：只认属于该租户的 Webhook
                .filter(webhook -> webhook.getTenantId().equals(key.tenantId()))
                .map(ControlPlaneWebhookSubscriptions::toSubscription)
                .toList();
    }

    private static WebhookSubscription toSubscription(InternalWebhook webhook) {
        return new WebhookSubscription(
                webhook.getId(),
                webhook.getUrl(),
                webhook.getEvents().stream()
                        .map(InternalWebhook.EventsEnum::getValue)
                        .collect(Collectors.toSet()),
                webhook.getEnabled(),
                webhook.getSigningSecret());
    }

    /** 缓存键。 */
    private record Key(String tenantId, String projectId) {}
}
