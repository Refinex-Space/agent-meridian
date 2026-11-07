package cn.refinex.meridian.runtime.stream.api;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.EventFeeder;
import cn.refinex.meridian.runtime.support.SseTestClient;
import cn.refinex.meridian.runtime.support.SseTestClient.Connection;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 单连接时长上限（架构设计「SSE 协议」：默认 60 分钟，到期由服务端关闭，客户端带 {@code Last-Event-ID} 重连）。
 * 上限缩短为 2 秒验证机制，默认值由 {@code SsePropertiesTest} 核对。
 */
@TestPropertySource(
        properties = {
            "meridian.runtime.sse.max-connection-duration=2s",
            "meridian.runtime.sse.ping-interval=500ms",
            "meridian.runtime.sse.reconcile-interval=500ms"
        })
class EventStreamLimitsIT extends ApiTestBase {

    @Autowired
    private ApplicationContext context;

    @Test
    void theServerClosesTheConnectionAtTheLimitAndTheClientResumesWithLastEventId() {
        String sessionId = createSession(null);
        String thread = api(ApiTestKeys.KEY_A)
                .listThreads(sessionId, null)
                .body()
                .get("data")
                .get(0)
                .get("id")
                .asString();
        EventFeeder feeder = new EventFeeder(context);
        feeder.published(sessionId, thread, "1");
        SseTestClient sse = new SseTestClient(port, ApiTestKeys.KEY_A);

        long start = System.nanoTime();
        Connection first = sse.open("/v1/sessions/" + sessionId + "/stream", null);
        first.await(frames -> first.completed(), Duration.ofSeconds(10));
        double seconds = (System.nanoTime() - start) / 1e9;
        assertThat(seconds).isBetween(1.5, 6.0);
        assertThat(first.seqs()).containsExactly(1L);
        assertThat(first.errors()).isEmpty();

        feeder.published(sessionId, thread, "2");
        Connection second = sse.open("/v1/sessions/" + sessionId + "/stream", "1");
        try {
            second.await(frames -> second.seqs().contains(2L), Duration.ofSeconds(10));
            assertThat(second.seqs()).containsExactly(2L);
        } finally {
            second.close();
        }
    }
}
