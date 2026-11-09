package cn.refinex.meridian.runtime.stream.api;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 没有任何事件的会话，SSE 的响应头也立即到达（架构设计「SSE 协议」：连接建立后先发注释 {@code :open}）。
 * 回归 P7 发现的问题：WebFlux 写出第一条消息时才提交响应头，安静的会话要等第一个 {@code ping}（默认 15 秒），
 * 等 {@code onopen} 之后再发消息的客户端每次卡 15 秒。本类用默认的 ping 间隔。
 */
class EventStreamOpenIT extends ApiTestBase {

    @Test
    void headersArriveImmediatelyOnAQuietSession() throws Exception {
        String sessionId = createSession(null);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/sessions/" + sessionId + "/stream"))
                .header("Authorization", "Bearer " + ApiTestKeys.KEY_A)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            long start = System.nanoTime();
            HttpResponse<InputStream> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(elapsed).as("不等 15 秒的第一个 ping").isLessThan(Duration.ofSeconds(5));
            try (InputStream body = response.body();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                assertThat(reader.readLine()).isEqualTo(":open");
            }
        }
    }
}
