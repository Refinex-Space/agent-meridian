package cn.refinex.meridian.persistence.redis;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.id.Ids;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class RedisMessagingIT {

    private final RedisMessaging messaging = RedisFixtures.messaging();
    private final ReactiveRedisMessaging reactive = RedisFixtures.reactiveMessaging();

    @Test
    void consumerGroupReadsAcksAndRedeliversUnackedMessages() {
        String stream = "it:" + Ids.newUlid();
        String group = "billing";
        messaging.publish(stream, Map.of("id", "m0"));
        // 组建立前写入的消息也能读到
        messaging.ensureGroup(stream, group);
        messaging.ensureGroup(stream, group);
        messaging.publish(stream, Map.of("id", "m1"));

        List<StreamMessage> first = messaging.readGroup(stream, group, "c1", 10, Duration.ZERO);
        assertThat(first).extracting(m -> m.field("id")).containsExactly("m0", "m1");
        assertThat(messaging.ack(stream, group, List.of(first.getFirst().recordId())))
                .isEqualTo(1);

        // c1 没有确认 m1 就"崩溃"了：新消息不会再投给别人，空闲超时后由 c2 认领重投
        assertThat(messaging.readGroup(stream, group, "c2", 10, Duration.ZERO)).isEmpty();
        assertThat(messaging.claimIdle(stream, group, "c2", Duration.ofHours(1), 10))
                .isEmpty();
        List<StreamMessage> claimed = messaging.claimIdle(stream, group, "c2", Duration.ZERO, 10);
        assertThat(claimed).extracting(m -> m.field("id")).containsExactly("m1");
        assertThat(messaging.ack(
                        stream,
                        group,
                        claimed.stream().map(StreamMessage::recordId).toList()))
                .isEqualTo(1);
        assertThat(messaging.claimIdle(stream, group, "c3", Duration.ZERO, 10)).isEmpty();
    }

    @Test
    void readGroupBlocksUntilTimeoutWhenEmpty() {
        String stream = "it:" + Ids.newUlid();
        messaging.ensureGroup(stream, "g");

        long start = System.nanoTime();
        assertThat(messaging.readGroup(stream, "g", "c1", 10, Duration.ofMillis(300)))
                .isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(250));
    }

    @Test
    void reactiveClientHasTheSameSemantics() {
        String stream = "it:" + Ids.newUlid();
        String group = "audit";

        StepVerifier.create(reactive.ensureGroup(stream, group)
                        .then(reactive.ensureGroup(stream, group))
                        .then(reactive.publish(stream, Map.of("id", "r1")))
                        .thenMany(reactive.readGroup(stream, group, "c1", 10, Duration.ZERO)))
                .assertNext(m -> assertThat(m.field("id")).isEqualTo("r1"))
                .verifyComplete();

        StepVerifier.create(reactive.claimIdle(stream, group, "c2", Duration.ZERO, 10)
                        .flatMap(m -> reactive.ack(stream, group, List.of(m.recordId()))))
                .expectNext(1L)
                .verifyComplete();
        StepVerifier.create(reactive.claimIdle(stream, group, "c3", Duration.ZERO, 10))
                .verifyComplete();
    }

    @Test
    void boundedPublishTrimsTheStreamAndRefreshesItsExpiry() {
        String stream = "it:bounded:" + Ids.newUlid();

        Flux.range(0, 300)
                .concatMap(i -> reactive.publish(stream, Map.of("seq", String.valueOf(i)), 10, Duration.ofMinutes(5)))
                .blockLast(Duration.ofSeconds(30));

        StringRedisTemplate template = RedisFixtures.template();
        assertThat(template.opsForStream().size(stream))
                .as("近似裁剪按宏节点整块删除，长度远小于写入数")
                .isLessThan(300);
        assertThat(template.getExpire(stream, TimeUnit.SECONDS)).isBetween(1L, 300L);
    }

    @Test
    void pubSubDeliversToBlockingAndReactiveSubscribers() throws InterruptedException {
        String channel = "it." + Ids.newUlid();
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        try (RedisMessaging.Subscription ignored = messaging.subscribe(channel, message -> {
            if (!PROBE.equals(message)) {
                received.add(message);
                latch.countDown();
            }
        })) {
            Flux<String> reactiveSubscription =
                    reactive.subscribe(channel).filter(m -> !PROBE.equals(m)).take(1);
            StepVerifier.create(reactiveSubscription)
                    .then(() -> {
                        awaitSubscribers(channel, 2);
                        messaging.notify(channel, "key.revoked:key_1");
                    })
                    .expectNext("key.revoked:key_1")
                    .verifyComplete();
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(received).containsExactly("key.revoked:key_1");
    }

    private static final String PROBE = "probe";

    /** 订阅是异步建立的：反复发布探测消息，直到收到它的订阅者数量达到预期，有 5 秒上限。 */
    private void awaitSubscribers(String channel, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (messaging.notify(channel, PROBE) < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("订阅未在 5 秒内建立");
            }
            Thread.onSpinWait();
        }
    }
}
