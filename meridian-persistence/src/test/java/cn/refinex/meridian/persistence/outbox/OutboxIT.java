package cn.refinex.meridian.persistence.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.persistence.PersistenceFixtures;
import cn.refinex.meridian.persistence.redis.RedisFixtures;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import cn.refinex.meridian.persistence.redis.RedisStreamOutboxPublisher;
import cn.refinex.meridian.persistence.redis.StreamMessage;
import cn.refinex.meridian.testsupport.TestDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** T02-A05：回滚不发布、多实例并发只发布一次、消费方重复收到时幂等；T16（D49 C-12）：认领后在事务外发布，失败不阻塞队头。 */
class OutboxIT {

    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");

    private DataSource dataSource;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate transactions;
    private MutableClock clock;
    private OutboxWriter writer;
    private JdbcClient jdbc;

    record UsageEvent(String tenantId, String model, long inputTokens) {}

    @BeforeEach
    void setUp() {
        dataSource = PersistenceFixtures.dataSource();
        TestDatabase.truncate(PersistenceFixtures.SCHEMA);
        transactionManager = PersistenceFixtures.transactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        clock = MutableClock.at(NOW);
        writer = new OutboxWriter(dataSource, PersistenceFixtures.SCHEMA, clock);
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void rolledBackBusinessTransactionPublishesNothing() {
        RecordingPublisher publisher = new RecordingPublisher();
        OutboxRelay relay = relay(publisher, 10);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    writer.append("usage", new UsageEvent("ten_A", "qwen-plus", 10));
                    throw new IllegalStateException("业务失败");
                }))
                .hasMessage("业务失败");

        assertThat(relay.relayOnce()).isZero();
        assertThat(publisher.published).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM rt.outbox").query(Long.class).single())
                .isZero();
    }

    @Test
    void committedMessageIsPublishedOnceAndMarked() {
        RecordingPublisher publisher = new RecordingPublisher();
        OutboxRelay relay = relay(publisher, 10);
        String id = transactions.execute(status -> writer.append("usage", new UsageEvent("ten_A", "qwen-plus", 10)));
        clock.advance(Duration.ofSeconds(1));

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(relay.relayOnce()).isZero();

        OutboxMessage message = publisher.published.peek();
        assertThat(message.id()).isEqualTo(id);
        assertThat(message.topic()).isEqualTo("usage");
        assertThat(message.createdAt()).isEqualTo(NOW);
        assertThat(JsonMappers.lenient().readValue(message.payload(), UsageEvent.class))
                .isEqualTo(new UsageEvent("ten_A", "qwen-plus", 10));
        assertThat(jdbc.sql("SELECT published_at FROM rt.outbox WHERE id = :id")
                        .param("id", id)
                        .query(java.time.OffsetDateTime.class)
                        .single()
                        .toInstant())
                .isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void appendOutsideTransactionIsRejected() {
        assertThatThrownBy(() -> writer.append("usage", Map.of("x", 1))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailingMessageDoesNotBlockTheMessagesBehindIt() {
        List<String> ids = transactions.execute(status -> IntStream.range(0, 3)
                .mapToObj(i -> writer.append("audit", Map.of("seq", i)))
                .toList());
        FlakyPublisher publisher = new FlakyPublisher(ids.get(1));
        OutboxRelay relay = relay(publisher, 10);

        // D49 C-12：失败的消息只记一次失败，同批后面的照常发布
        assertThat(relay.relayOnce()).isEqualTo(2);
        assertThat(attempts(ids.get(1))).isEqualTo(1);
        publisher.healthy = true;
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(publisher.published)
                .extracting(OutboxMessage::id)
                .containsExactly(ids.get(0), ids.get(2), ids.get(1));
    }

    @Test
    void consecutiveFailuresEndTheBatchAndReleaseTheRest() {
        List<String> ids = transactions.execute(status -> IntStream.range(0, 5)
                .mapToObj(i -> writer.append("usage", Map.of("seq", i)))
                .toList());
        FlakyPublisher publisher = new FlakyPublisher(null);
        OutboxRelay relay = relay(publisher, 10);

        assertThat(relay.relayUntilDrained()).isZero();
        assertThat(ids).extracting(this::attempts).containsExactly(1, 1, 1, 0, 0);
        assertThat(jdbc.sql("SELECT count(*) FROM rt.outbox WHERE claimed_until IS NOT NULL")
                        .query(Long.class)
                        .single())
                .isZero();
        publisher.healthy = true;
        assertThat(relay.relayUntilDrained()).isEqualTo(5);
    }

    @Test
    void aMessageThatKeepsFailingIsNoLongerDelivered() {
        String id = transactions.execute(status -> writer.append("audit", Map.of("seq", 0)));
        FlakyPublisher publisher = new FlakyPublisher(id);
        OutboxRelay relay = relay(publisher, 10);

        for (int i = 0; i < OutboxRelay.MAX_ATTEMPTS; i++) {
            assertThat(relay.relayOnce()).isZero();
        }
        publisher.healthy = true;
        assertThat(relay.relayOnce()).isZero();
        assertThat(attempts(id)).isEqualTo(OutboxRelay.MAX_ATTEMPTS);
        assertThat(publisher.published).isEmpty();
    }

    @Test
    void anUnavailablePublishTargetNeverExhaustsTheAttemptsOfAMessage() {
        // R2 审查 K-03：Redis 中断时每一轮都失败，最老的消息不能因此达到上限、永久停止投递
        List<String> ids = transactions.execute(status -> IntStream.range(0, 4)
                .mapToObj(i -> writer.append("usage", Map.of("seq", i)))
                .toList());
        UnavailablePublisher publisher = new UnavailablePublisher();
        OutboxRelay relay = relay(publisher, 10);

        for (int i = 0; i < OutboxRelay.MAX_ATTEMPTS * 2; i++) {
            assertThat(relay.relayUntilDrained()).isZero();
        }
        assertThat(ids).extracting(this::attempts).containsOnly(0);
        assertThat(publisher.calls).as("每一轮在第一条失败后就结束").isEqualTo(OutboxRelay.MAX_ATTEMPTS * 2);
        assertThat(jdbc.sql("SELECT count(*) FROM rt.outbox WHERE claimed_until IS NOT NULL")
                        .query(Long.class)
                        .single())
                .isZero();

        publisher.healthy = true;
        assertThat(relay.relayUntilDrained()).isEqualTo(4);
    }

    @Test
    void publishingHoldsNoTransactionAndNoRowLock() {
        String id = transactions.execute(status -> writer.append("usage", Map.of("seq", 0)));
        List<Boolean> observations = new ArrayList<>();
        OutboxPublisher probe = message -> {
            observations.add(TransactionSynchronizationManager.isActualTransactionActive());
            // 另一个连接能立即锁住这一行：Relay 没有在发布期间持有行锁
            transactions.executeWithoutResult(
                    status -> observations.add(jdbc.sql("SELECT id FROM rt.outbox WHERE id = :id FOR UPDATE NOWAIT")
                            .param("id", id)
                            .query(String.class)
                            .optional()
                            .isPresent()));
        };

        assertThat(relay(probe, 10).relayOnce()).isEqualTo(1);
        assertThat(observations).containsExactly(false, true);
    }

    @Test
    void aClaimThatIsNeverMarkedExpiresAndTheMessageIsRedelivered() {
        String id = transactions.execute(status -> writer.append("usage", Map.of("seq", 0)));
        // 另一个实例认领后崩溃：认领截止时间还没到
        jdbc.sql("UPDATE rt.outbox SET claimed_until = :until WHERE id = :id")
                .param(
                        "until",
                        java.time.OffsetDateTime.ofInstant(
                                NOW.plus(OutboxRelay.CLAIM_DURATION), java.time.ZoneOffset.UTC))
                .param("id", id)
                .update();
        RecordingPublisher publisher = new RecordingPublisher();
        OutboxRelay relay = relay(publisher, 10);

        assertThat(relay.relayOnce()).isZero();
        clock.advance(OutboxRelay.CLAIM_DURATION.plusSeconds(1));
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(publisher.published).extracting(OutboxMessage::id).containsExactly(id);
    }

    @Test
    void concurrentRelaysPublishEachMessageExactlyOnce() throws Exception {
        int total = 300;
        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < total; i++) {
                writer.append("usage", Map.of("seq", i));
            }
        });
        RecordingPublisher publisher = new RecordingPublisher(Duration.ofMillis(1));
        OutboxRelay relayA = relay(publisher, 25);
        OutboxRelay relayB = relay(publisher, 25);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> a = executor.submit(() -> drain(relayA, start));
            Future<Integer> b = executor.submit(() -> drain(relayB, start));
            start.countDown();
            int publishedByA = a.get();
            int publishedByB = b.get();

            assertThat(publishedByA + publishedByB).isEqualTo(total);
            // 两个实例确实都参与了投递，而不是一个实例独占
            assertThat(publishedByA).isPositive();
            assertThat(publishedByB).isPositive();
        }
        List<String> ids = publisher.published.stream().map(OutboxMessage::id).toList();
        assertThat(ids).hasSize(total).doesNotHaveDuplicates();
        assertThat(jdbc.sql("SELECT count(*) FROM rt.outbox WHERE published_at IS NULL")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void consumerIsIdempotentWhenTheSameMessageArrivesTwice() {
        RedisMessaging messaging = RedisFixtures.messaging();
        String topic = "usage-it-" + System.nanoTime();
        String group = "billing";
        messaging.ensureGroup(topic, group);
        RedisStreamOutboxPublisher publisher = new RedisStreamOutboxPublisher(messaging);
        transactions.executeWithoutResult(status -> writer.append(topic, new UsageEvent("ten_A", "qwen-plus", 42)));

        // 模拟 Relay 发布成功后、标记前崩溃：同一条 outbox 消息被发布两次
        OutboxMessage message = jdbc.sql("SELECT id, topic, payload::text AS payload, created_at FROM rt.outbox")
                .query((rs, row) -> new OutboxMessage(
                        rs.getString("id"),
                        rs.getString("topic"),
                        rs.getString("payload"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)
                                .toInstant()))
                .single();
        publisher.publish(message);
        assertThat(relay(publisher, 10).relayOnce()).isEqualTo(1);

        // 消费者 c1 读到两条，处理第一条后崩溃（未确认）
        List<StreamMessage> delivered = messaging.readGroup(topic, group, "c1", 10, Duration.ZERO);
        assertThat(delivered).hasSize(2);
        consume(delivered.getFirst());

        // c2 认领未确认的消息重新处理：两条都是同一个 outbox ID
        List<StreamMessage> redelivered = messaging.claimIdle(topic, group, "c2", Duration.ZERO, 10);
        assertThat(redelivered).hasSize(2);
        redelivered.forEach(this::consume);
        messaging.ack(
                topic, group, redelivered.stream().map(StreamMessage::recordId).toList());

        assertThat(jdbc.sql("SELECT message_id FROM rt.t02_consumed_messages")
                        .query(String.class)
                        .list())
                .containsExactly(message.id());
        assertThat(messaging.claimIdle(topic, group, "c3", Duration.ZERO, 10)).isEmpty();
    }

    /** 幂等消费：按 outbox 消息 ID 去重入库，重复收到时不产生第二条记录。 */
    private void consume(StreamMessage message) {
        jdbc.sql("INSERT INTO rt.t02_consumed_messages (message_id, payload) VALUES (:id, :payload)"
                        + " ON CONFLICT (message_id) DO NOTHING")
                .param("id", message.field(RedisStreamOutboxPublisher.FIELD_ID))
                .param("payload", message.field(RedisStreamOutboxPublisher.FIELD_PAYLOAD))
                .update();
    }

    private int attempts(String id) {
        return jdbc.sql("SELECT attempts FROM rt.outbox WHERE id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private OutboxRelay relay(OutboxPublisher publisher, int batchSize) {
        return new OutboxRelay(dataSource, transactionManager, PersistenceFixtures.SCHEMA, publisher, clock, batchSize);
    }

    private static int drain(OutboxRelay relay, CountDownLatch start) throws InterruptedException {
        start.await();
        int total = 0;
        int published;
        do {
            published = relay.relayOnce();
            total += published;
        } while (published > 0);
        return total;
    }

    /** 记录收到的消息；可以模拟发布耗时，让两个 Relay 的批次在时间上重叠。 */
    static final class RecordingPublisher implements OutboxPublisher {
        final ConcurrentLinkedQueue<OutboxMessage> published = new ConcurrentLinkedQueue<>();
        private final Duration latency;

        RecordingPublisher() {
            this(Duration.ZERO);
        }

        RecordingPublisher(Duration latency) {
            this.latency = latency;
        }

        @Override
        public void publish(OutboxMessage message) {
            if (!latency.isZero()) {
                try {
                    Thread.sleep(latency);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            published.add(message);
        }
    }

    /** 发布目标不可用：抛 Redis 连接失败（瞬时错误），直到被标记为恢复。 */
    static final class UnavailablePublisher implements OutboxPublisher {
        int calls;
        volatile boolean healthy;

        @Override
        public void publish(OutboxMessage message) {
            if (!healthy) {
                calls++;
                throw new RedisConnectionFailureException("Unable to connect to Redis");
            }
        }
    }

    /** 在指定消息上失败（为空时在所有消息上失败），直到被标记为恢复。 */
    static final class FlakyPublisher implements OutboxPublisher {
        final List<OutboxMessage> published = new ArrayList<>();
        private final String failingId;
        volatile boolean healthy;

        FlakyPublisher(String failingId) {
            this.failingId = failingId;
        }

        @Override
        public void publish(OutboxMessage message) {
            if (!healthy && (failingId == null || message.id().equals(failingId))) {
                throw new IllegalStateException("Redis 不可用");
            }
            published.add(message);
        }
    }
}
