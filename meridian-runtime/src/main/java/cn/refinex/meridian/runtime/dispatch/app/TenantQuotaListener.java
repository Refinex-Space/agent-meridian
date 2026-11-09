package cn.refinex.meridian.runtime.dispatch.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnQueueRepository;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;

/**
 * 订阅 {@code config.invalidate}（架构设计「跨服务数据流」，ADR-0042）：收到 {@code tenant_quota} 时重新读取该租户的并发配额，
 * 更新已有的 {@code rt.tenant_concurrency.max_running}。每个节点都会收到并执行同一个更新，结果相同。不认识的类型忽略，由 T15b 起
 * 各自的订阅者处理。
 *
 * <p>Pub/Sub 不保证送达：消息丢失时 {@code max_running} 要等下一次配额变更才更新。
 */
public class TenantQuotaListener implements SmartLifecycle {

    /** Pub/Sub 频道。 */
    public static final String CHANNEL = "config.invalidate";

    /** 本订阅者处理的消息类型。 */
    public static final String TENANT_QUOTA = "tenant_quota";

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaListener.class);

    private final ReactiveRedisMessaging redis;
    private final TenantQuotaPort quotas;
    private final TurnQueueRepository queue;
    private final Clock clock;
    private Disposable subscription;

    /**
     * 创建订阅者。
     *
     * @param redis Redis 消息封装
     * @param quotas 配额来源
     * @param queue 轮次队列仓储（租户并发计数）
     * @param clock 时钟
     */
    public TenantQuotaListener(
            ReactiveRedisMessaging redis, TenantQuotaPort quotas, TurnQueueRepository queue, Clock clock) {
        this.redis = redis;
        this.quotas = quotas;
        this.queue = queue;
        this.clock = clock;
    }

    /**
     * 处理一条消息：读取配额并更新计数行。
     *
     * @param message 消息 JSON
     */
    public void apply(String message) {
        JsonNode json = JsonMappers.lenient().readTree(message);
        if (!TENANT_QUOTA.equals(json.path("type").asString(null))) {
            return;
        }
        String tenantId = json.path("tenant_id").asString(null);
        if (tenantId == null) {
            return;
        }
        int maxRunning = quotas.maxConcurrentTurns(tenantId);
        boolean updated = queue.updateTenantMaxRunning(tenantId, maxRunning, clock.instant());
        log.info("租户并发配额已更新 tenantId={} maxRunning={} counterExists={}", tenantId, maxRunning, updated);
    }

    @Override
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        subscription = redis.subscribe(CHANNEL)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30)))
                .concatMap(message -> Blocking.run(() -> apply(message)).onErrorResume(error -> {
                    log.warn("处理配置变更失败 message={}", message, error);
                    return Mono.empty();
                }))
                .subscribe(ignored -> {}, error -> log.error("配置变更频道的订阅意外结束", error));
        log.info("已订阅配置变更频道 channel={}", CHANNEL);
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
