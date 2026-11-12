package cn.refinex.meridian.runtime.retention.app;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 数据生命周期作业的配置（{@code meridian.runtime.retention}，架构设计「数据保留」，ADR-0049）。
 *
 * @param enabled 是否在本节点运行三个作业（测试关掉后手动驱动）
 * @param purgeInterval 会话清理的扫描间隔
 * @param purgeBatchSize 每次扫描至多处理的已删除会话数
 * @param purgeWebhookGrace 清理等待该会话 Webhook 投递结束的最长时间（自删除时算起），默认 12 小时：重试计划总长约 10.7 小时，
 *     清理仍在删除后 24 小时内完成
 * @param archiveInterval 事件归档的扫描间隔
 * @param archiveAfter 会话无活动多久后归档事件，默认 90 天
 * @param archiveBatchSize 每次扫描至多归档的会话数
 * @param expiryInterval 事件保留清理的扫描间隔
 * @param expiryBatchSize 每个租户每次扫描至多处理的会话数
 * @param defaultEventRetentionDays 没有配置 control-plane 时的保留天数，默认 365（与 control-plane 的新租户默认值相同）
 */
@ConfigurationProperties("meridian.runtime.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60s") Duration purgeInterval,
        @DefaultValue("20") int purgeBatchSize,
        @DefaultValue("12h") Duration purgeWebhookGrace,
        @DefaultValue("1h") Duration archiveInterval,
        @DefaultValue("90d") Duration archiveAfter,
        @DefaultValue("20") int archiveBatchSize,
        @DefaultValue("1h") Duration expiryInterval,
        @DefaultValue("100") int expiryBatchSize,
        @DefaultValue("365") int defaultEventRetentionDays) {

    /**
     * 校验取值。
     *
     * @throws IllegalArgumentException 取值不合法时
     */
    public RetentionProperties {
        if (purgeBatchSize < 1 || archiveBatchSize < 1 || expiryBatchSize < 1 || defaultEventRetentionDays < 1) {
            throw new IllegalArgumentException("meridian.runtime.retention 的批大小与默认保留天数至少为 1");
        }
        if (purgeWebhookGrace.compareTo(Duration.ofHours(23)) > 0) {
            throw new IllegalArgumentException(
                    "meridian.runtime.retention.purge-webhook-grace 不能超过 23 小时：删除要在 24 小时内完成");
        }
    }
}
