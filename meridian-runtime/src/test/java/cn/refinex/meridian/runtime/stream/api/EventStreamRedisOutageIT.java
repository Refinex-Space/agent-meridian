package cn.refinex.meridian.runtime.stream.api;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.EventFeeder;
import cn.refinex.meridian.runtime.support.SseTestClient;
import cn.refinex.meridian.runtime.support.SseTestClient.Connection;
import cn.refinex.meridian.runtime.support.SseTestClient.Frame;
import cn.refinex.meridian.testsupport.MeridianContainers;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * SSE 在 Redis 短暂中断时不丢不重（T07-A03 的第三种情况）：中断期间 XADD 失败、Redis Stream 读不到新条目，事件由每秒一次的
 * 数据库对账补发；恢复后实时推送照常，迟到的条目按 seq 去重。
 *
 * <p>中断用 {@code docker pause} 冻结一个本测试独占的 Redis 容器，不影响其他测试共用的单例容器。
 */
@SpringBootTest(
        classes = RuntimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "meridian.runtime.sse.reconcile-interval=1s",
            "meridian.runtime.sse.ping-interval=1s",
            // 冻结期间的 Redis 命令很快超时，测试不必等 Lettuce 默认的 60 秒
            "spring.data.redis.timeout=2s"
        })
@ActiveProfiles("test")
@DirtiesContext
class EventStreamRedisOutageIT {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(MeridianContainers.REDIS_IMAGE)).withExposedPorts(6379);

    private static final Duration WAIT = Duration.ofSeconds(30);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.minio(registry);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        ApiTestKeys.register(registry);
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MeterRegistry meters;

    @Test
    void eventsWrittenWhileRedisIsDownStillArriveContinuouslyWithoutDuplicates() {
        ApiTestClient api = new ApiTestClient(port, ApiTestKeys.KEY_A);
        String sessionId = api.createSession(
                        "{\"agent\": \"agt_dev_assistant\", \"environment\": \"env_dev_none\"}", null)
                .text("id");
        String thread = api.listThreads(sessionId, null)
                .body()
                .get("data")
                .get(0)
                .get("id")
                .asString();
        EventFeeder feeder = new EventFeeder(context);
        Connection stream =
                new SseTestClient(port, ApiTestKeys.KEY_A).open("/v1/sessions/" + sessionId + "/stream", null);
        try {
            stream.await(frames -> frames.stream().anyMatch(f -> f.event().equals("ping")), WAIT);
            for (int i = 1; i <= 5; i++) {
                feeder.published(sessionId, thread, "before-" + i);
            }
            stream.await(frames -> seqs(frames).size() >= 5, WAIT);

            double publishFailures = publishFailures();
            pause();
            try {
                for (int i = 1; i <= 5; i++) {
                    // 事务照常提交，提交后的 XADD 在 2 秒后超时失败，只记指标
                    feeder.published(sessionId, thread, "during-" + i);
                }
                stream.await(frames -> seqs(frames).size() >= 10, WAIT);
                // XADD 在 2 秒后超时：等到失败被记录，证明这 5 个事件确实没有经过 Redis
                long deadline = System.nanoTime() + WAIT.toNanos();
                while (publishFailures() <= publishFailures && System.nanoTime() < deadline) {
                    sleep(Duration.ofMillis(100));
                }
                assertThat(publishFailures()).as("Redis 冻结期间 XADD 失败").isGreaterThan(publishFailures);
            } finally {
                unpause();
            }

            for (int i = 1; i <= 5; i++) {
                feeder.published(sessionId, thread, "after-" + i);
            }
            stream.await(frames -> seqs(frames).size() >= 15, WAIT);
            // 留出时间让冻结期间排队的命令在恢复后执行完，迟到的条目不能造成重复
            sleep(Duration.ofSeconds(3));

            assertThat(stream.seqs())
                    .containsExactlyElementsOf(
                            LongStream.rangeClosed(1, 15).boxed().toList());
            assertThat(stream.errors()).isEmpty();
        } finally {
            stream.close();
        }
    }

    private double publishFailures() {
        return meters.counter("meridian.event.publish.failures").count();
    }

    private static List<Long> seqs(List<Frame> frames) {
        return frames.stream().filter(f -> f.id() != null).map(Frame::seq).toList();
    }

    private static void pause() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void unpause() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
