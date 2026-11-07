package cn.refinex.meridian.runtime.stream.api;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.runtime.session.domain.NewThread;
import cn.refinex.meridian.runtime.session.infra.ThreadRepository;
import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.EventFeeder;
import cn.refinex.meridian.runtime.support.SseTestClient;
import cn.refinex.meridian.runtime.support.SseTestClient.Connection;
import cn.refinex.meridian.runtime.support.SseTestClient.Frame;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * SSE 的正确性：事件不丢、不重、按序（T07-A03 的前两种情况，Redis 中断见 {@code EventStreamRedisOutageIT}）、
 * {@code Last-Event-ID} 与增量事件、ping（T07-A04）、线程流与 {@code ?threads=main} 不误判缺失（T07-A10）。
 *
 * <p>对账与 ping 的间隔缩短为 1 秒，测试不必等 5 秒、15 秒；默认值由 {@code SsePropertiesTest} 核对。
 */
@TestPropertySource(
        properties = {"meridian.runtime.sse.reconcile-interval=1s", "meridian.runtime.sse.ping-interval=1s"})
class EventStreamIT extends ApiTestBase {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MeterRegistry meters;

    private EventFeeder feeder;
    private SseTestClient sse;

    @BeforeEach
    void setUp() {
        feeder = new EventFeeder(context);
        sse = new SseTestClient(port, ApiTestKeys.KEY_A);
    }

    @Test
    void eventsCommittedWithoutXaddAreStillDeliveredContinuously() {
        String sessionId = createSession(null);
        String thread = mainThread(sessionId);
        Connection stream = sse.open(streamPath(sessionId, ""), null);
        try {
            awaitSubscribed(stream);
            double gapsBefore = gapRefills();
            feeder.published(sessionId, thread, "1");
            feeder.committedWithoutXadd(sessionId, thread, "2");
            feeder.committedWithoutXadd(sessionId, thread, "3");
            feeder.published(sessionId, thread, "4");
            // 末尾的事件没有后续的 XADD 触发跳号，只能由对账补发
            feeder.committedWithoutXadd(sessionId, thread, "5");

            stream.await(frames -> seqsOf(frames).size() >= 5, WAIT);
            assertThat(stream.seqs()).containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(texts(stream.frames())).containsExactly("1", "2", "3", "4", "5");
            assertThat(gapRefills()).as("跳号回补与对账补发都计入指标").isGreaterThanOrEqualTo(gapsBefore + 2);
        } finally {
            stream.close();
        }
    }

    @Test
    void clientDisconnectingDuringABurstResumesWithLastEventIdWithoutLossOrDuplicates() throws Exception {
        String sessionId = createSession(null);
        String thread = mainThread(sessionId);
        int total = 300;
        Connection first = sse.open(streamPath(sessionId, ""), null);
        awaitSubscribed(first);
        CompletableFuture<Void> burst = CompletableFuture.runAsync(() -> {
            for (int i = 1; i <= total; i++) {
                if (i % 7 == 0) {
                    feeder.committedWithoutXadd(sessionId, thread, Integer.toString(i));
                } else {
                    feeder.published(sessionId, thread, Integer.toString(i));
                }
            }
        });
        List<Long> received = new ArrayList<>();
        try {
            first.await(frames -> seqsOf(frames).size() >= 60, WAIT);
        } finally {
            first.close();
        }
        received.addAll(first.seqs());
        String lastEventId = Long.toString(received.getLast());

        Connection second = sse.open(streamPath(sessionId, ""), lastEventId);
        try {
            burst.get();
            second.await(frames -> !seqsOf(frames).isEmpty() && seqsOf(frames).getLast() == total, WAIT);
            assertThat(second.seqs().getFirst()).isEqualTo(received.getLast() + 1);
            received.addAll(second.seqs());
        } finally {
            second.close();
        }
        assertThat(received)
                .containsExactlyElementsOf(
                        LongStream.rangeClosed(1, total).boxed().toList());
        assertThat(new HashSet<>(received)).hasSize(total);
    }

    @Test
    void lastEventIdReplaysOnlyLaterEventsAndDeltasArriveOnlyWhenRequested() {
        String sessionId = createSession(null);
        String thread = mainThread(sessionId);
        for (int i = 1; i <= 10; i++) {
            feeder.published(sessionId, thread, Integer.toString(i));
        }

        Connection resumed = sse.open(streamPath(sessionId, "?event_deltas=true"), "6");
        Connection plain = sse.open(streamPath(sessionId, ""), "6");
        try {
            resumed.await(frames -> seqsOf(frames).size() >= 4, WAIT);
            plain.await(frames -> seqsOf(frames).size() >= 4, WAIT);
            assertThat(resumed.seqs()).containsExactly(7L, 8L, 9L, 10L);
            awaitSubscribed(resumed);
            awaitSubscribed(plain);

            feeder.delta(sessionId, thread, "增量");
            feeder.published(sessionId, thread, "11");
            resumed.await(
                    frames -> frames.stream().anyMatch(f -> f.event().equals("event_delta"))
                            && seqsOf(frames).contains(11L),
                    WAIT);
            plain.await(frames -> seqsOf(frames).contains(11L), WAIT);

            Frame delta = resumed.frames().stream()
                    .filter(f -> f.event().equals("event_delta"))
                    .findFirst()
                    .orElseThrow();
            assertThat(delta.id()).as("只在流上的事件没有 id").isNull();
            assertThat(delta.envelope().get("data").get("delta").asString()).isEqualTo("增量");
            assertThat(plain.frames()).noneMatch(f -> f.event().startsWith("event_"));
            assertThat(plain.seqs()).containsExactly(7L, 8L, 9L, 10L, 11L);
        } finally {
            resumed.close();
            plain.close();
        }
    }

    @Test
    void pingsArriveOnSchedule() {
        String sessionId = createSession(null);
        long start = System.nanoTime();
        Connection stream = sse.open(streamPath(sessionId, ""), null);
        try {
            List<Frame> frames = stream.await(f -> pings(f) >= 3, WAIT);
            double seconds = (System.nanoTime() - start) / 1e9;
            assertThat(seconds).as("1 秒一次，第 3 次在约 3 秒时到达").isBetween(2.5, 6.0);
            Frame ping = frames.stream()
                    .filter(f -> f.event().equals("ping"))
                    .findFirst()
                    .orElseThrow();
            assertThat(ping.id()).isNull();
            assertThat(ping.envelope().get("session_id").asString()).isEqualTo(sessionId);
        } finally {
            stream.close();
        }
    }

    @Test
    void threadStreamsAndMainFilterDoNotTreatOtherThreadsSeqsAsMissing() {
        String sessionId = createSession(null);
        String main = mainThread(sessionId);
        String child = Ids.newId(IdPrefix.THREAD);
        PlatformContexts.run(
                projectA(),
                () -> context.getBean(ThreadRepository.class)
                        .insert(
                                new NewThread(child, sessionId, main, "toolu_spawn_1", "researcher"),
                                context.getBean(Clock.class).instant()));

        Connection childStream = sse.open("/v1/sessions/" + sessionId + "/threads/" + child + "/stream", null);
        Connection mainStream = sse.open("/v1/sessions/" + sessionId + "/threads/" + main + "/stream", null);
        Connection mainFilter = sse.open(streamPath(sessionId, "?threads=main"), null);
        Connection all = sse.open(streamPath(sessionId, ""), null);
        try {
            awaitSubscribed(childStream);
            awaitSubscribed(mainStream);
            awaitSubscribed(mainFilter);
            awaitSubscribed(all);
            double gapsBefore = gapRefills();
            int rounds = 40;
            for (int i = 1; i <= rounds; i++) {
                // 子线程连续产生事件，主线程只偶尔产生：主线程流看到的 seq 大段跳跃
                feeder.published(sessionId, child, "c" + i);
                feeder.published(sessionId, child, "c" + i + "'");
                if (i % 5 == 0) {
                    feeder.published(sessionId, main, "m" + i);
                }
            }
            long total = rounds * 2 + rounds / 5;
            all.await(frames -> seqsOf(frames).size() >= total, WAIT);
            childStream.await(frames -> seqsOf(frames).size() >= rounds * 2, WAIT);
            mainStream.await(frames -> seqsOf(frames).size() >= rounds / 5, WAIT);
            mainFilter.await(frames -> seqsOf(frames).size() >= rounds / 5, WAIT);
            // 再等过几次对账：对账不能把其他线程的序号当成缺失
            sleep(Duration.ofMillis(2500));

            assertThat(all.seqs())
                    .containsExactlyElementsOf(
                            LongStream.rangeClosed(1, total).boxed().toList());
            assertThat(childStream.seqs())
                    .hasSize(rounds * 2)
                    .doesNotHaveDuplicates()
                    .isSorted();
            assertThat(texts(childStream.frames())).allMatch(text -> text.startsWith("c"));
            assertThat(mainStream.seqs())
                    .hasSize(rounds / 5)
                    .doesNotHaveDuplicates()
                    .isSorted();
            assertThat(texts(mainStream.frames())).allMatch(text -> text.startsWith("m"));
            assertThat(mainFilter.seqs()).isEqualTo(mainStream.seqs());
            assertThat(gapRefills()).as("没有误判的跳号回补").isEqualTo(gapsBefore);
        } finally {
            childStream.close();
            mainStream.close();
            mainFilter.close();
            all.close();
        }
    }

    private String mainThread(String sessionId) {
        return api(ApiTestKeys.KEY_A)
                .listThreads(sessionId, null)
                .body()
                .get("data")
                .get(0)
                .get("id")
                .asString();
    }

    /** 等第一个 ping 到达：此时补发已完成，连接已在 Redis Stream 上登记。 */
    private static void awaitSubscribed(Connection stream) {
        stream.await(frames -> pings(frames) >= 1, WAIT);
    }

    private double gapRefills() {
        return meters.counter("meridian.sse.gap.refills").count();
    }

    private static String streamPath(String sessionId, String query) {
        return "/v1/sessions/" + sessionId + "/stream" + query;
    }

    private static List<Long> seqsOf(List<Frame> frames) {
        return frames.stream().filter(f -> f.id() != null).map(Frame::seq).toList();
    }

    private static long pings(List<Frame> frames) {
        return frames.stream().filter(f -> f.event().equals("ping")).count();
    }

    private static List<String> texts(List<Frame> frames) {
        return frames.stream()
                .filter(f -> f.event().equals("agent.message"))
                .map(f -> f.envelope()
                        .get("data")
                        .get("content")
                        .get(0)
                        .get("text")
                        .asString())
                .toList();
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
