package cn.refinex.meridian.persistence.jdbc;

import cn.refinex.meridian.persistence.flyway.MigrationConventions;
import cn.refinex.meridian.persistence.idempotency.Idempotency;
import cn.refinex.meridian.persistence.idempotency.IdempotencyPurger;
import cn.refinex.meridian.persistence.idempotency.IdempotencyStore;
import cn.refinex.meridian.persistence.outbox.OutboxPublisher;
import cn.refinex.meridian.persistence.outbox.OutboxRelay;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.persistence.redis.RedisMessagingAutoConfiguration;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 本模块的 Spring Boot 自动配置：迁移约定、默认时钟、outbox 写入与投递、幂等键，以及它们的后台清理任务。
 * 只在设置了 {@code meridian.persistence.schema} 的应用中生效。
 *
 * <p>数据源、{@code JdbcClient}、事务管理器沿用 Spring Boot 的自动配置，这里不重复定义。
 */
@AutoConfiguration(
        after = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            RedisMessagingAutoConfiguration.class
        })
@ConditionalOnProperty(prefix = "meridian.persistence", name = "schema")
@EnableConfigurationProperties(PersistenceProperties.class)
public class PersistenceAutoConfiguration {

    /** 已发布 outbox 消息的保留时长：留一周供排查，之后删除，防止表无限增长。 */
    static final Duration OUTBOX_RETENTION = Duration.ofDays(7);

    /** 过期数据清理的执行间隔。 */
    static final Duration PURGE_INTERVAL = Duration.ofHours(1);

    /** 每次清理最多删除的行数，避免长事务。 */
    static final int PURGE_LIMIT = 10_000;

    /**
     * 迁移约定：脚本位置与 schema（编码规范「数据访问」）。
     *
     * @param properties 持久化配置
     * @return Flyway 配置定制器
     */
    @Bean
    public MigrationConventions meridianMigrationConventions(PersistenceProperties properties) {
        return new MigrationConventions(properties.schema());
    }

    /**
     * 默认时钟（UTC 系统时钟）。应用或测试定义自己的 {@link Clock} 时以其为准（编码规范「通用能力复用」）。
     *
     * @return 系统时钟
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * outbox 写入器。
     *
     * @param dataSource 数据源
     * @param properties 持久化配置
     * @param clock 时钟
     * @return 写入器
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter outboxWriter(DataSource dataSource, PersistenceProperties properties, Clock clock) {
        return new OutboxWriter(dataSource, properties.schema(), clock);
    }

    /**
     * outbox 投递器，存在 {@link OutboxPublisher}（默认由 Redis 自动配置提供）时创建。
     *
     * @param dataSource 数据源
     * @param transactionManager 事务管理器
     * @param properties 持久化配置
     * @param publisher 发布目标
     * @param clock 时钟
     * @return 投递器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(OutboxPublisher.class)
    public OutboxRelay outboxRelay(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            PersistenceProperties properties,
            OutboxPublisher publisher,
            Clock clock) {
        return new OutboxRelay(
                dataSource,
                transactionManager,
                properties.schema(),
                publisher,
                clock,
                properties.outbox().batchSize());
    }

    /**
     * 定时运行 outbox 投递。
     *
     * @param relay 投递器
     * @param properties 持久化配置
     * @return 后台任务
     */
    @Bean
    @ConditionalOnBean(OutboxRelay.class)
    @ConditionalOnBooleanProperty(name = "meridian.persistence.outbox.relay-enabled", matchIfMissing = true)
    public PeriodicJob outboxRelayJob(OutboxRelay relay, PersistenceProperties properties) {
        return new PeriodicJob("outbox-relay", properties.outbox().pollInterval(), relay::relayUntilDrained);
    }

    /**
     * 定时删除已发布超过 {@link #OUTBOX_RETENTION} 的 outbox 消息。
     *
     * @param relay 投递器
     * @param clock 时钟
     * @return 后台任务
     */
    @Bean
    @ConditionalOnBean(OutboxRelay.class)
    public PeriodicJob outboxPurgeJob(OutboxRelay relay, Clock clock) {
        return new PeriodicJob(
                "outbox-purge",
                PURGE_INTERVAL,
                () -> relay.purgePublishedBefore(clock.instant().minus(OUTBOX_RETENTION), PURGE_LIMIT));
    }

    /**
     * 幂等键存储。
     *
     * @param dataSource 数据源
     * @param properties 持久化配置
     * @return 存储
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore idempotencyStore(DataSource dataSource, PersistenceProperties properties) {
        return new IdempotencyStore(dataSource, properties.schema());
    }

    /**
     * 幂等执行器。
     *
     * @param store 幂等键存储
     * @param clock 时钟
     * @return 执行器
     */
    @Bean
    @ConditionalOnMissingBean
    public Idempotency idempotency(IdempotencyStore store, Clock clock) {
        return new Idempotency(store, clock);
    }

    /**
     * 幂等键过期清理器。
     *
     * @param dataSource 数据源
     * @param properties 持久化配置
     * @return 清理器
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyPurger idempotencyPurger(DataSource dataSource, PersistenceProperties properties) {
        return new IdempotencyPurger(dataSource, properties.schema());
    }

    /**
     * 定时删除过期的幂等键记录。
     *
     * @param purger 清理器
     * @param clock 时钟
     * @return 后台任务
     */
    @Bean
    public PeriodicJob idempotencyPurgeJob(@Qualifier("idempotencyPurger") IdempotencyPurger purger, Clock clock) {
        return new PeriodicJob(
                "idempotency-purge", PURGE_INTERVAL, () -> purger.purgeExpired(clock.instant(), PURGE_LIMIT));
    }
}
