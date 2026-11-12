package cn.refinex.meridian.knowledge.web;

import cn.refinex.meridian.persistence.redis.RedisMessaging;
import cn.refinex.meridian.security.apikey.ControlPlaneApiKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 订阅 {@code key.revoked}（架构设计「认证」，ADR-0042）：control-plane 吊销 API Key 后广播 Key ID，本节点淘汰校验缓存，1 秒内拒绝
 * 该 Key。消息丢失时缓存最长 60 秒后过期。
 */
public class ApiKeyRevocationListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRevocationListener.class);

    private final RedisMessaging redis;
    private final ControlPlaneApiKeyResolver resolver;
    private RedisMessaging.Subscription subscription;

    /**
     * 创建订阅者。
     *
     * @param redis Redis 消息封装
     * @param resolver 带缓存的 API Key 解析器；为空表示 API Key 不经 control-plane 校验，不需要订阅
     */
    public ApiKeyRevocationListener(RedisMessaging redis, ControlPlaneApiKeyResolver resolver) {
        this.redis = redis;
        this.resolver = resolver;
    }

    @Override
    public synchronized void start() {
        if (subscription == null && resolver != null) {
            // 淘汰缓存只遍历内存中的条目，直接在订阅容器的线程上执行
            subscription = redis.subscribe(ControlPlaneApiKeyResolver.REVOKED_CHANNEL, resolver::evict);
            log.info("已订阅 API Key 吊销频道 channel={}", ControlPlaneApiKeyResolver.REVOKED_CHANNEL);
        }
    }

    @Override
    public synchronized void stop() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return subscription != null;
    }

    @Override
    public boolean isAutoStartup() {
        return resolver != null;
    }
}
