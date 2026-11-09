package cn.refinex.meridian.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.security.servicejwt.ServiceClientProperties;
import cn.refinex.meridian.security.servicejwt.ServiceRestClients;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ControlPlaneApiKeyResolver}：只传哈希、带服务凭据，结果（含无效）缓存 60 秒，吊销通知淘汰，控制面故障为 503 且不缓存
 * （架构设计「认证」，ADR-0042）。用 JDK 自带的 HTTP 服务模拟 control-plane 的内部接口。
 */
class ControlPlaneApiKeyResolverTest {

    private static final String KEY = "mk_live_AbCdEfGh0123456789";

    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicLong nanos = new AtomicLong();
    private final MutableClock clock = MutableClock.at(Instant.parse("2026-11-09T00:00:00Z"));
    private HttpServer server;
    private ControlPlaneApiKeyResolver resolver;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/api-keys:verify", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
            String response = responses.getOrDefault("verify", "404");
            int status = response.startsWith("{") ? 200 : Integer.parseInt(response);
            byte[] bytes = response.startsWith("{") ? response.getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ServiceRestClients clients = new ServiceRestClients(
                new ServiceClientProperties(null, null, Duration.ofSeconds(2), Duration.ofSeconds(5)), () -> "svc_out");
        resolver = new ControlPlaneApiKeyResolver(
                clients.create("http://127.0.0.1:" + server.getAddress().getPort()), clock, nanos::get);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void validKeysAreResolvedFromTheHashAndCachedForSixtySeconds() {
        responses.put("verify", verification("null"));

        ApiKeyPrincipal principal = resolver.resolve(KEY).orElseThrow();

        assertThat(principal.apiKeyId()).isEqualTo("key_1");
        assertThat(principal.scopes()).containsExactly(ApiKeyScope.SESSIONS_READ);
        assertThat(principal.rateLimitPerMinute()).isEqualTo(600);
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request).startsWith("Bearer svc_out ");
            assertThat(request).contains(ApiKeyHashes.sha256(KEY)).doesNotContain(KEY);
        });

        resolver.resolve(KEY);
        assertThat(requests).hasSize(1);
        nanos.addAndGet(Duration.ofSeconds(61).toNanos());
        resolver.resolve(KEY);
        assertThat(requests).hasSize(2);
    }

    @Test
    void revocationEvictsTheKeyImmediately() {
        responses.put("verify", verification("null"));
        assertThat(resolver.resolve(KEY)).isPresent();
        responses.put("verify", "404");

        resolver.evict("key_other");
        assertThat(resolver.resolve(KEY)).isPresent();
        resolver.evict("key_1");
        assertThat(resolver.resolve(KEY)).isEmpty();
    }

    @Test
    void invalidKeysAreCachedToo() {
        assertThat(resolver.resolve(KEY)).isEmpty();
        assertThat(resolver.resolve(KEY)).isEmpty();
        assertThat(requests).hasSize(1);
    }

    @Test
    void expiredKeysAreRejectedEvenWhileCached() {
        responses.put("verify", verification("\"2026-11-09T00:00:30Z\""));
        assertThat(resolver.resolve(KEY)).isPresent();

        clock.advance(Duration.ofSeconds(30));

        assertThat(resolver.resolve(KEY)).isEmpty();
    }

    @Test
    void controlPlaneFailuresAreServiceUnavailableAndNotCached() {
        responses.put("verify", "500");

        assertThatThrownBy(() -> resolver.resolve(KEY))
                .isInstanceOfSatisfying(
                        MeridianException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
        responses.put("verify", verification("null"));
        assertThat(resolver.resolve(KEY)).isPresent();
    }

    private static String verification(String expiresAt) {
        return """
                {"api_key_id":"key_1","tenant_id":"ten_1","project_id":"prj_1","scopes":["sessions:read","future:scope"],
                 "rate_limit_per_minute":600,"expires_at":%s}
                """.formatted(expiresAt);
    }
}
