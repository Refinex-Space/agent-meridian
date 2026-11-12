package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.runtime.webhook.app.WebhookSubscriptionPort;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;

/**
 * 订阅 {@code config.invalidate}，处理 {@code webhook}（ADR-0049）：淘汰该租户缓存的 Webhook 订阅，修改与删除立即对新的投递生效。
 * 消息丢失时由缓存的过期时间（{@code subscription-cache-ttl}）兜底。
 */
public final class WebhookInvalidationListener implements SmartLifecycle {

    /** 消息类型。 */
    static final String TYPE = "webhook";

    private static final Logger log = LoggerFactory.getLogger(WebhookInvalidationListener.class);

    private final ReactiveRedisMessaging redis;
    private final WebhookSubscriptionPort subscriptions;
    private Disposable subscription;

    /**
     * 创建订阅者。
     *
     * @param redis Redis 消息封装
     * @param subscriptions 订阅来源
     */
    public WebhookInvalidationListener(ReactiveRedisMessaging redis, WebhookSubscriptionPort subscriptions) {
        this.redis = redis;
        this.subscriptions = subscriptions;
    }

    /**
     * 处理一条消息。
     *
     * @param message 消息 JSON：{@code {"type":…,"tenant_id":…,"id":…}}
     */
    public void apply(String message) {
        JsonNode json = JsonMappers.lenient().readTree(message);
        String tenantId = json.path("tenant_id").asString(null);
        if (TYPE.equals(json.path("type").asString(null)) && tenantId != null) {
            subscriptions.evictTenant(tenantId);
            log.debug("Webhook 订阅缓存已淘汰 tenantId={}", tenantId);
        }
    }

    @Override
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        subscription = redis.subscribe(CatalogInvalidationListener.CHANNEL)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30)))
                .concatMap(message -> Blocking.run(() -> apply(message)).onErrorResume(error -> {
                    log.warn("处理 Webhook 配置变更失败 message={}", message, error);
                    return Mono.empty();
                }))
                .subscribe(ignored -> {}, error -> log.error("配置变更频道的订阅意外结束", error));
    }

    @Override
    public synchronized void stop() {
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return subscription != null;
    }
}
