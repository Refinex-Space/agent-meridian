package cn.refinex.meridian.controlplane.tenant.app;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.controlplane.web.AfterCommit;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 配置变更的广播（架构设计「跨服务数据流」中的 {@code config.invalidate}，ADR-0042）：事务提交后发布，runtime 收到后重新读取。
 * Pub/Sub 不保证送达，发布失败只记 WARN。
 */
@Component
public class ConfigInvalidations {

    /** Pub/Sub 频道。 */
    public static final String CHANNEL = "config.invalidate";

    /** 消息类型：租户配额变化，runtime 更新 {@code rt.tenant_concurrency.max_running}。 */
    public static final String TENANT_QUOTA = "tenant_quota";

    private static final Logger log = LoggerFactory.getLogger(ConfigInvalidations.class);

    private final RedisMessaging redis;

    /**
     * 创建广播。
     *
     * @param redis Redis 消息封装
     */
    public ConfigInvalidations(RedisMessaging redis) {
        this.redis = redis;
    }

    /**
     * 租户配额变化：在当前事务提交后发布。
     *
     * @param tenantId 租户 ID
     */
    public void tenantQuotaChanged(String tenantId) {
        String message = JsonMappers.lenient().writeValueAsString(Map.of("type", TENANT_QUOTA, "tenant_id", tenantId));
        AfterCommit.run(() -> {
            try {
                redis.notify(CHANNEL, message);
            } catch (RuntimeException e) {
                log.warn("发布租户配额变更失败，runtime 要等下一次变更才更新 tenantId={}", tenantId, e);
            }
        });
    }
}
