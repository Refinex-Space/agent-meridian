package cn.refinex.meridian.controlplane.catalog.infra;

import cn.refinex.meridian.controlplane.catalog.app.ProviderProbe;
import cn.refinex.meridian.controlplane.catalog.domain.ConnectivityCheck;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderType;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 用 JDK 的 HTTP 客户端探测供应商（ADR-0053）：OpenAI 兼容 {@code GET {base}/models}（{@code Authorization: Bearer}）、Anthropic
 * {@code GET /v1/models}（{@code x-api-key}）、Gemini {@code GET /v1beta/models}（{@code x-goog-api-key}）。不跟随重定向、不走代理，
 * 连接 5 秒、整体 10 秒超时；响应体不读出，说明里不出现凭据与响应内容。端点由调用方先按地址策略校验。
 */
@Component
public class HttpProviderProbe implements ProviderProbe {

    /** Anthropic 的官方端点。 */
    static final String ANTHROPIC_BASE = "https://api.anthropic.com";

    /** Gemini 的官方端点。 */
    static final String GEMINI_BASE = "https://generativelanguage.googleapis.com";

    /** Anthropic 要求的 API 版本头。 */
    static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final Logger log = LoggerFactory.getLogger(HttpProviderProbe.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();
    private final Clock clock;

    /**
     * 创建探测器。
     *
     * @param clock 时钟
     */
    public HttpProviderProbe(Clock clock) {
        this.clock = clock;
    }

    @Override
    public ConnectivityCheck probe(ProviderType provider, String baseUrl, String apiKey) {
        HttpRequest request = request(provider, baseUrl, apiKey);
        Instant started = clock.instant();
        long begin = System.nanoTime();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return ConnectivityCheck.ofHttpStatus(response.statusCode(), elapsed(begin), started);
        } catch (IOException e) {
            // 只给分类说明，不回显异常消息（R2 审查 K-04）
            log.info("供应商连通性测试连接失败 error={}", e.toString());
            return new ConnectivityCheck(
                    ConnectivityCheck.UNREACHABLE, null, elapsed(begin), ConnectivityCheck.failureReason(e), started);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConnectivityCheck(ConnectivityCheck.ERROR, null, elapsed(begin), "探测被中断", started);
        }
    }

    /** 各类型列模型清单的请求。 */
    static HttpRequest request(ProviderType provider, String baseUrl, String apiKey) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder().timeout(REQUEST_TIMEOUT).GET();
        switch (provider) {
            case OPENAI_COMPATIBLE -> {
                builder.uri(URI.create(trim(baseUrl) + "/models"));
                if (apiKey != null) {
                    builder.header("Authorization", "Bearer " + apiKey);
                }
            }
            case ANTHROPIC -> {
                builder.uri(URI.create(trim(baseUrl == null ? ANTHROPIC_BASE : baseUrl) + "/v1/models"));
                builder.header("anthropic-version", ANTHROPIC_VERSION);
                if (apiKey != null) {
                    builder.header("x-api-key", apiKey);
                }
            }
            case GEMINI -> {
                builder.uri(URI.create(trim(baseUrl == null ? GEMINI_BASE : baseUrl) + "/v1beta/models"));
                if (apiKey != null) {
                    builder.header("x-goog-api-key", apiKey);
                }
            }
        }
        return builder.build();
    }

    private static String trim(String baseUrl) {
        String base = baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static long elapsed(long begin) {
        return Duration.ofNanos(System.nanoTime() - begin).toMillis();
    }
}
