package cn.refinex.meridian.runtime.web;

import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.security.apikey.ControlPlaneApiKeyResolver;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

/**
 * 订阅 {@code key.revoked}（架构设计「认证」，ADR-0042）：control-plane 吊销 API Key 后广播 Key ID，本节点淘汰校验缓存，1 秒内拒绝
 * 该 Key。消息丢失时缓存最长 60 秒后过期。
 */
public class ApiKeyRevocationListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRevocationListener.class);

    private final ReactiveRedisMessaging redis;
    private final ControlPlaneApiKeyResolver resolver;
    private Disposable subscription;

    /**
     * 创建订阅者。
     *
     * @param redis Redis 消息封装
     * @param resolver 带缓存的 API Key 解析器
     */
    public ApiKeyRevocationListener(ReactiveRedisMessaging redis, ControlPlaneApiKeyResolver resolver) {
        this.redis = redis;
        this.resolver = resolver;
    }

    @Override
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        // 淘汰缓存只遍历内存中的条目，不阻塞，直接在订阅线程上执行
        subscription = redis.subscribe(ControlPlaneApiKeyResolver.REVOKED_CHANNEL)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30)))
                .subscribe(resolver::evict, error -> log.error("API Key 吊销频道的订阅意外结束", error));
        log.info("已订阅 API Key 吊销频道 channel={}", ControlPlaneApiKeyResolver.REVOKED_CHANNEL);
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
