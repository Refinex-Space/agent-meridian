package cn.refinex.meridian.persistence.jdbc;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 持久化配置，前缀 {@code meridian.persistence}。设置了 {@code schema} 的应用才启用本模块的自动配置。
 *
 * @param schema 服务自己的 schema：control-plane 为 {@code cp}，runtime 为 {@code rt}，knowledge 为 {@code kb}
 * @param outbox outbox 投递配置
 */
@ConfigurationProperties("meridian.persistence")
public record PersistenceProperties(
        String schema, @DefaultValue Outbox outbox) {

    /**
     * 校验 schema 名。
     *
     * @throws IllegalArgumentException schema 名不合法时
     */
    public PersistenceProperties {
        SchemaNames.require(schema);
    }

    /**
     * outbox 投递配置。
     *
     * @param relayEnabled 是否在本实例运行 OutboxRelay，默认开启；多实例同时运行不会重复投递
     * @param pollInterval 两轮投递之间的间隔，默认 500 毫秒：用量与审计允许秒级延迟，取半秒兼顾延迟与数据库负载
     * @param batchSize 每批最多取出的消息数，默认 100
     */
    public record Outbox(
            @DefaultValue("true") boolean relayEnabled,
            @DefaultValue("500ms") Duration pollInterval,
            @DefaultValue("100") int batchSize) {}
}
