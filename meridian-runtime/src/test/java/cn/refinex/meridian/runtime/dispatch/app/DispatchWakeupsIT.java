package cn.refinex.meridian.runtime.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.runtime.dispatch.app.DispatchWakeups.WakeupSource;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnQueueRepository;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.testsupport.MeridianContainers;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * 唤醒：轮次入队提交后经 Redis 频道 {@code dispatch.wakeup} 通知其他节点，Worker 不必等到轮询；没有通知时每个轮询周期发出一次信号。
 */
class DispatchWakeupsIT {

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveRedisMessaging redis;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                MeridianContainers.redis().getHost(), MeridianContainers.redis().getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new ReactiveRedisMessaging(new ReactiveStringRedisTemplate(connectionFactory));
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void enqueueOnOneNodeWakesWorkersOnOtherNodesBeforeThePoll() {
        // 轮询周期设为 1 小时：收到的信号只可能来自通知
        DispatchWakeups worker =
                new DispatchWakeups(redis, Duration.ofHours(1), reactor.core.scheduler.Schedulers.parallel());
        DispatchWakeups publisher =
                new DispatchWakeups(redis, Duration.ofHours(1), reactor.core.scheduler.Schedulers.parallel());
        MutableClock clock = MutableClock.at(Instant.parse("2100-01-01T00:00:00Z"));
        TurnEnqueuer enqueuer = new TurnEnqueuer(
                new TurnQueueRepository(RuntimeTestDatabase.dataSource()),
                RuntimeTestDatabase.transactions(),
                publisher,
                DispatchProperties.defaults(),
                clock);

        Mono<WakeupSource> firstSignal = worker.signals().next().cache();
        Disposable subscription = firstSignal.subscribe();
        try {
            // SUBSCRIBE 是异步建立的：持续入队新会话的轮次，直到 Worker 收到第一个通知
            Disposable enqueueing = Flux.interval(Duration.ofMillis(200))
                    .flatMap(tick -> Mono.fromCallable(() ->
                                    enqueuer.enqueue(TestSessions.create(clock).turn("唤醒")))
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
                    .subscribe();
            try {
                assertThat(firstSignal.block(Duration.ofSeconds(10))).isEqualTo(WakeupSource.NOTIFIED);
            } finally {
                enqueueing.dispose();
            }
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void withoutNotificationsWorkersArePolledEveryInterval() {
        StepVerifier.withVirtualTime(() -> new DispatchWakeups(redis, Duration.ofSeconds(1), VirtualTimeScheduler.get())
                        .signals()
                        .filter(WakeupSource.POLLED::equals)
                        .take(3))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(999))
                .thenAwait(Duration.ofMillis(1))
                .expectNext(WakeupSource.POLLED)
                .thenAwait(Duration.ofSeconds(2))
                .expectNext(WakeupSource.POLLED, WakeupSource.POLLED)
                .verifyComplete();
    }

    @Test
    void signalsArrivingWhileTheWorkerIsBusyCollapseIntoOne() {
        StepVerifier.withVirtualTime(
                        () -> new DispatchWakeups(redis, Duration.ofSeconds(1), VirtualTimeScheduler.get()).signals(),
                        0)
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(5))
                .thenRequest(1)
                .expectNext(WakeupSource.POLLED)
                .thenRequest(1)
                .expectNoEvent(Duration.ofMillis(999))
                .thenAwait(Duration.ofMillis(1))
                .expectNext(WakeupSource.POLLED)
                .thenCancel()
                .verify();
    }
}
