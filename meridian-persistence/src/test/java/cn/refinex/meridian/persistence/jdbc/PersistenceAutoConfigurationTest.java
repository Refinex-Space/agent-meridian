package cn.refinex.meridian.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.persistence.flyway.MigrationConventions;
import cn.refinex.meridian.persistence.idempotency.Idempotency;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.persistence.objectstore.ObjectStoreAutoConfiguration;
import cn.refinex.meridian.persistence.outbox.OutboxPublisher;
import cn.refinex.meridian.persistence.outbox.OutboxRelay;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.persistence.redis.RedisMessagingAutoConfiguration;
import java.time.Clock;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** 自动配置的装配条件；不连数据库，后台任务关闭。 */
class PersistenceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceTransactionManagerAutoConfiguration.class,
                    RedisMessagingAutoConfiguration.class,
                    PersistenceAutoConfiguration.class,
                    ObjectStoreAutoConfiguration.class))
            .withBean(DataSource.class, SimpleDriverDataSource::new)
            .withPropertyValues("meridian.persistence.outbox.relay-enabled=false");

    @Test
    void nothingIsCreatedWithoutSchema() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OutboxWriter.class);
            assertThat(context).doesNotHaveBean(ObjectStore.class);
        });
    }

    @Test
    void schemaEnablesConventionsOutboxAndIdempotency() {
        runner.withPropertyValues("meridian.persistence.schema=rt").run(context -> {
            assertThat(context).hasSingleBean(OutboxWriter.class);
            assertThat(context).hasSingleBean(Idempotency.class);
            assertThat(context).hasSingleBean(Clock.class);
            // 没有 Redis 时没有发布器，也就没有投递器
            assertThat(context).doesNotHaveBean(OutboxRelay.class);

            FluentConfiguration flyway = new FluentConfiguration();
            context.getBean(MigrationConventions.class).customize(flyway);
            assertThat(flyway.getSchemas()).containsExactly("rt");
            assertThat(flyway.getDefaultSchema()).isEqualTo("rt");
            assertThat(flyway.getLocations()).extracting(Object::toString).containsExactly("classpath:db/migration/rt");
            assertThat(flyway.isCreateSchemas()).isFalse();
        });
    }

    @Test
    void relayIsCreatedWhenAPublisherExists() {
        runner.withPropertyValues("meridian.persistence.schema=rt")
                .withBean(OutboxPublisher.class, () -> message -> {})
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxRelay.class);
                    assertThat(context).doesNotHaveBean("outboxRelayJob");
                    assertThat(context).hasBean("outboxPurgeJob");
                });
    }

    @Test
    void invalidSchemaFailsFast() {
        runner.withPropertyValues("meridian.persistence.schema=rt;drop")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void objectStoreIsCreatedWhenBucketIsConfigured() {
        runner.withPropertyValues(
                        "meridian.persistence.objectstore.endpoint=http://localhost:19000",
                        "meridian.persistence.objectstore.region=us-east-1",
                        "meridian.persistence.objectstore.bucket=meridian",
                        "meridian.persistence.objectstore.access-key=a",
                        "meridian.persistence.objectstore.secret-key=b",
                        "meridian.persistence.objectstore.path-style-access=true")
                .run(context ->
                        assertThat(context.getBean(ObjectStore.class).bucket()).isEqualTo("meridian"));
    }
}
