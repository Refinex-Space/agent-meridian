package cn.refinex.meridian.controlplane.apikey.app;

import cn.refinex.meridian.controlplane.web.AfterCommit;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import cn.refinex.meridian.security.apikey.ControlPlaneApiKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * API Key 吊销的广播（架构设计「认证」与「跨服务数据流」中的 {@code key.revoked}，ADR-0042）：事务提交后发布 Key ID，各服务淘汰
 * 校验缓存。发布失败只记 WARN，其他服务的缓存最长 60 秒后过期。
 */
@Component
public class ApiKeyRevocations {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRevocations.class);

    private final RedisMessaging redis;

    /**
     * 创建广播。
     *
     * @param redis Redis 消息封装
     */
    public ApiKeyRevocations(RedisMessaging redis) {
        this.redis = redis;
    }

    /**
     * 一个 Key 在当前事务中被吊销：提交后广播。
     *
     * @param apiKeyId API Key ID
     */
    public void revoked(String apiKeyId) {
        AfterCommit.run(() -> {
            try {
                redis.notify(ControlPlaneApiKeyResolver.REVOKED_CHANNEL, apiKeyId);
            } catch (RuntimeException e) {
                log.warn("广播 API Key 吊销失败，其他服务的缓存最长 60 秒后过期 apiKeyId={}", apiKeyId, e);
            }
        });
    }
}
