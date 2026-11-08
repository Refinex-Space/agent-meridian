package cn.refinex.meridian.engine.agentscope.model;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 供应商 HTTP 传输：AgentScope {@link HttpTransport} 的平台实现，基于 JDK {@link HttpClient}，供 OpenAI 兼容的模型类使用。
 *
 * <p>与 2.0.4 自带的 {@code JdkHttpTransport} 行为一致（SSE 的 {@code data:} 行、{@code [DONE]} 结束、首包与空闲两种超时、
 * 取消时关闭连接），区别有两点：
 * <ul>
 *   <li>收到响应时把状态码与 {@code Retry-After} 记到 Reactor Context 中的 {@link ProviderResponseObserver}，自带实现在
 *       流式路径上丢弃响应头，{@code RoutedModel} 拿不到 429 的等待时间（架构设计「模型调用」中的重试）。
 *   <li>每个模型工厂一个实例、不注册为 AgentScope 的全局默认传输，生命周期由平台管理。
 * </ul>
 *
 * <p>不记录请求与响应正文（日志规范：不记录提示词）；错误响应的正文最多保留 {@value #MAX_ERROR_BODY_CHARS} 个字符，供分类与排查。
 *
 * <p>线程安全：可被并发调用。
 */
public final class ProviderHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(ProviderHttpTransport.class);

    /** 建立连接的超时。 */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 从发出请求到收到第一个 SSE 数据的超时。推理模型的首包可能较慢，取 120 秒。 */
    public static final Duration DEFAULT_FIRST_CHUNK_TIMEOUT = Duration.ofSeconds(120);

    /** 两个 SSE 数据之间的最长间隔。 */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(60);

    /** 错误响应正文保留的最大字符数。 */
    static final int MAX_ERROR_BODY_CHARS = 4_096;

    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE_MARKER = "[DONE]";
    private static final String RETRY_AFTER = "Retry-After";

    private final HttpClient client;
    private final Duration firstChunkTimeout;
    private final Duration idleTimeout;
    private final Clock clock;

    /**
     * 使用默认超时创建传输。
     *
     * @param clock 时钟，解析 HTTP 日期格式的 {@code Retry-After} 时使用
     */
    public ProviderHttpTransport(Clock clock) {
        this(clock, DEFAULT_CONNECT_TIMEOUT, DEFAULT_FIRST_CHUNK_TIMEOUT, DEFAULT_IDLE_TIMEOUT);
    }

    /**
     * 创建传输。
     *
     * @param clock 时钟
     * @param connectTimeout 连接超时
     * @param firstChunkTimeout 首包超时，也是非流式请求的读超时
     * @param idleTimeout 流式数据的空闲超时
     */
    public ProviderHttpTransport(
            Clock clock, Duration connectTimeout, Duration firstChunkTimeout, Duration idleTimeout) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.firstChunkTimeout = Objects.requireNonNull(firstChunkTimeout, "firstChunkTimeout");
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .build();
    }

    @Override
    public HttpResponse execute(HttpRequest request) {
        java.net.http.HttpRequest jdkRequest =
                toJdkRequest(request).timeout(firstChunkTimeout).build();
        try {
            java.net.http.HttpResponse<String> response = client.send(jdkRequest, BodyHandlers.ofString());
            HttpResponse.Builder builder =
                    HttpResponse.builder().statusCode(response.statusCode()).body(response.body());
            response.headers().map().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    builder.header(name, values.getFirst());
                }
            });
            return builder.build();
        } catch (IOException e) {
            throw new HttpTransportException("供应商请求失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpTransportException("供应商请求被中断", e);
        }
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        return Flux.deferContextual(context -> {
            ProviderResponseObserver observer = context.getOrDefault(ProviderResponseObserver.class, null);
            java.net.http.HttpRequest jdkRequest = toJdkRequest(request).build();
            long startNanos = System.nanoTime();
            return Mono.fromFuture(() -> client.sendAsync(jdkRequest, BodyHandlers.ofInputStream()), false)
                    .timeout(firstChunkTimeout)
                    .flatMapMany(response -> {
                        if (observer != null) {
                            observer.record(
                                    response.statusCode(),
                                    response.headers().firstValue(RETRY_AFTER).orElse(null),
                                    clock);
                        }
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            return errorBody(response);
                        }
                        return dataLines(response.body(), startNanos);
                    })
                    .onErrorMap(ProviderHttpTransport::toTransportException);
        });
    }

    @Override
    public void close() {
        client.close();
    }

    /** 读出错误响应的正文后以带状态码的 {@link HttpTransportException} 结束；读取在弹性线程上进行。 */
    private static Flux<String> errorBody(java.net.http.HttpResponse<InputStream> response) {
        int status = response.statusCode();
        return Mono.fromCallable(() -> readLimited(response.body()))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn("")
                .flatMapMany(body -> {
                    log.warn("供应商返回错误状态 status={}", status);
                    return Flux.error(new HttpTransportException("供应商返回状态 " + status, status, body));
                });
    }

    /** 按行读取 SSE，取出 {@code data:} 的内容，遇到 {@code [DONE]} 结束；阻塞读取在弹性线程上进行，取消时关闭连接。 */
    private Flux<String> dataLines(InputStream body, long startNanos) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        Duration firstChunkLeft = firstChunkTimeout.minus(elapsed);
        Flux<String> lines = Flux.using(
                        () -> body,
                        stream -> Flux.fromStream(
                                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).lines()),
                        ProviderHttpTransport::closeQuietly)
                .subscribeOn(Schedulers.boundedElastic());
        return lines.filter(line -> line.startsWith(SSE_DATA_PREFIX))
                .map(line -> line.substring(SSE_DATA_PREFIX.length()).trim())
                .takeWhile(data -> !SSE_DONE_MARKER.equals(data))
                .filter(data -> !data.isEmpty())
                .timeout(
                        Mono.delay(firstChunkLeft.isNegative() ? Duration.ZERO : firstChunkLeft),
                        data -> Mono.delay(idleTimeout));
    }

    private static java.net.http.HttpRequest.Builder toJdkRequest(HttpRequest request) {
        URI uri;
        try {
            uri = URI.create(request.getUrl());
        } catch (IllegalArgumentException e) {
            throw new HttpTransportException("供应商端点不是合法的 URL", e);
        }
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(uri);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            // 明文连接不做 h2c 升级：部分服务在升级时丢弃请求体（与 2.0.4 的 JdkHttpTransport 相同的处理）
            builder.version(HttpClient.Version.HTTP_1_1);
        }
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        String body = request.getBody();
        java.net.http.HttpRequest.BodyPublisher publisher = body == null
                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                : java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        return builder.method(request.getMethod().toUpperCase(java.util.Locale.ROOT), publisher);
    }

    private static Throwable toTransportException(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof HttpTransportException) {
            return cause;
        }
        if (cause instanceof TimeoutException) {
            return new HttpTransportException("供应商响应超时", cause);
        }
        if (cause instanceof IOException) {
            return new HttpTransportException("供应商连接失败：" + cause.getMessage(), cause);
        }
        return cause;
    }

    private static String readLimited(InputStream stream) throws IOException {
        try (stream) {
            byte[] bytes = stream.readNBytes(MAX_ERROR_BODY_CHARS * 4);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() > MAX_ERROR_BODY_CHARS ? text.substring(0, MAX_ERROR_BODY_CHARS) : text;
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("关闭供应商响应流失败", e);
        }
    }
}
