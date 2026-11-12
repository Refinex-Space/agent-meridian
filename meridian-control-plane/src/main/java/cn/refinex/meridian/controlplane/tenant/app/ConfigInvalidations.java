package cn.refinex.meridian.controlplane.tenant.app;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.controlplane.web.AfterCommit;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 配置变更的广播（架构设计「跨服务数据流」中的 {@code config.invalidate}，ADR-0042、ADR-0045）：事务提交后发布，runtime 收到后重新读取
 * 或淘汰缓存。
 * Pub/Sub 不保证送达，发布失败只记 WARN。
 */
@Component
public class ConfigInvalidations {

    /** Pub/Sub 频道。 */
    public static final String CHANNEL = "config.invalidate";

    /** 消息类型：租户配额变化，runtime 更新 {@code rt.tenant_concurrency.max_running}。 */
    public static final String TENANT_QUOTA = "tenant_quota";

    /** 消息类型：凭据轮换或删除，runtime 淘汰缓存的明文（ADR-0045）。 */
    public static final String SECRET = "secret";

    /** 消息类型：供应商账号变更，runtime 淘汰该账号下全部模型的目录缓存。 */
    public static final String PROVIDER_ACCOUNT = "provider_account";

    /** 消息类型：模型变更或删除。 */
    public static final String MODEL = "model";

    /** 消息类型：价格表发布新版本，runtime 淘汰引用它的模型，用量记录随之带上新版本。 */
    public static final String PRICE_BOOK = "price_book";

    /** 消息类型：智能体发布了新版本或被删除（{@code id} 为智能体 ID），runtime 淘汰当前版本与该智能体的版本缓存（ADR-0047）。 */
    public static final String AGENT = "agent";

    /** 消息类型：环境被修改（产生新修订）或删除，runtime 淘汰当前修订的缓存。 */
    public static final String ENVIRONMENT = "environment";

    /** 消息类型：技能上传了新版本或被删除。技能版本不可变，runtime 不缓存"最新版本"，只为完整起见发布。 */
    public static final String SKILL = "skill";

    /** 消息类型：MCP 服务变更或删除。已发布的版本持有发布时的快照，runtime 不需要淘汰，只为完整起见发布。 */
    public static final String MCP_SERVER = "mcp_server";

    /** 消息类型：租户或项目策略变更（{@code id} 为租户 ID 或项目 ID），runtime 淘汰权限规则的缓存。 */
    public static final String PERMISSION_POLICY = "permission_policy";

    /** 消息类型：租户设置变更（{@code id} 为租户 ID），runtime 淘汰租户设置并淘汰该租户已装配的智能体实例（D32）。 */
    public static final String TENANT_SETTINGS = "tenant_settings";

    /** 消息类型：Webhook 创建、修改或删除（{@code id} 为 Webhook ID），runtime 淘汰该租户缓存的订阅（ADR-0049）。 */
    public static final String WEBHOOK = "webhook";

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
        String message = JsonMappers.lenient().writeValueAsString(new Invalidation(TENANT_QUOTA, tenantId));
        AfterCommit.run(() -> {
            try {
                redis.notify(CHANNEL, message);
            } catch (RuntimeException e) {
                log.warn("发布租户配额变更失败，runtime 要等下一次变更才更新 tenantId={}", tenantId, e);
            }
        });
    }

    /**
     * 一个资源变更（ADR-0045 第 4 条、ADR-0047）：在当前事务提交后发布 {@code {"type":…,"tenant_id":…,"id":…}}。
     *
     * @param type 资源类型：{@link #SECRET}、{@link #PROVIDER_ACCOUNT}、{@link #MODEL}、{@link #PRICE_BOOK}、{@link #AGENT}、
     *     {@link #ENVIRONMENT}、{@link #SKILL}、{@link #MCP_SERVER}、{@link #PERMISSION_POLICY}、{@link #TENANT_SETTINGS}
     * @param tenantId 租户 ID
     * @param id 资源 ID
     */
    public void resourceChanged(String type, String tenantId, String id) {
        String message = JsonMappers.lenient().writeValueAsString(new ResourceInvalidation(type, tenantId, id));
        AfterCommit.run(() -> {
            try {
                redis.notify(CHANNEL, message);
            } catch (RuntimeException e) {
                log.warn("发布配置变更失败，runtime 要等缓存过期才更新 type={} tenantId={} id={}", type, tenantId, id, e);
            }
        });
    }

    /**
     * 资源变更的消息：{@code {"type":"…","tenant_id":"…","id":"…"}}，字段顺序固定。
     *
     * @param type 资源类型
     * @param tenantId 租户 ID
     * @param id 资源 ID
     */
    record ResourceInvalidation(String type, String tenantId, String id) {}

    /**
     * {@code config.invalidate} 的消息：{@code {"type":"…","tenant_id":"…"}}，字段顺序固定。
     *
     * @param type 变更类型
     * @param tenantId 租户 ID
     */
    record Invalidation(String type, String tenantId) {}
}
