package cn.refinex.meridian.runtime.webhook.infra;

import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.runtime.tooling.infra.GuardedResolverGroup;
import cn.refinex.meridian.runtime.webhook.app.WebhookSender;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.resolver.AddressResolverGroup;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.HttpResources;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Webhook 的 HTTP 发送（ADR-0049）：Reactor Netty，不复用连接、不走代理、不跟随重定向；地址先按 {@link EndpointPolicy} 校验协议、用户信息
 * 与 IP 字面量，主机名在连接时由 {@link GuardedResolverGroup} 校验解析结果（防 DNS 重绑定）。响应体不读取。
 *
 * <p>线程安全：可并发调用；{@link #close()} 释放 DNS 解析器。方法阻塞到响应头到达或超时，在虚拟线程上调用。
 */
public final class HttpWebhookSender implements WebhookSender, AutoCloseable {

    /** 请求的 {@code User-Agent}。 */
    static final String USER_AGENT = "AgentMeridian-Webhook/1.0";

    private static final Logger log = LoggerFactory.getLogger(HttpWebhookSender.class);

    private final EndpointPolicy policy;
    private final Duration timeout;
    private final GuardedResolverGroup resolver;
    private final HttpClient http;

    /**
     * 用系统 DNS 创建。
     *
     * @param policy 接收方地址的策略
     * @param timeout 单次请求的超时
     */
    public HttpWebhookSender(EndpointPolicy policy, Duration timeout) {
        this(policy, timeout, null);
    }

    /**
     * 指定 DNS 解析器组，测试用。
     *
     * @param policy 接收方地址的策略
     * @param timeout 单次请求的超时
     * @param dns 实际做 DNS 解析的解析器组；为空时用系统 DNS
     */
    HttpWebhookSender(EndpointPolicy policy, Duration timeout, AddressResolverGroup<InetSocketAddress> dns) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.resolver = dns == null
                ? GuardedResolverGroup.system(address -> !policy.permits(address))
                : new GuardedResolverGroup(dns, address -> !policy.permits(address));
        this.http = HttpClient.create(ConnectionProvider.newConnection())
                .runOn(HttpResources.get(), false)
                .resolver(resolver)
                .noProxy()
                .followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeout.toMillis())
                .responseTimeout(timeout)
                .headers(headers -> headers.set(HttpHeaderNames.USER_AGENT, USER_AGENT)
                        .set(HttpHeaderNames.CONTENT_TYPE, "application/json"));
    }

    @Override
    public Result send(String url, Map<String, String> headers, String body) {
        // 主机名留到连接时校验：这里只检查协议、用户信息与 IP 字面量
        Optional<String> rejected = policy.violation(url, host -> List.of());
        if (rejected.isPresent()) {
            return new Result(null, "the webhook URL is not allowed: " + rejected.get());
        }
        try {
            Integer status = http.headers(h -> headers.forEach(h::set))
                    .post()
                    .uri(URI.create(url.strip()))
                    .send(ByteBufFlux.fromString(Mono.just(body), StandardCharsets.UTF_8, ByteBufAllocator.DEFAULT))
                    // 只要状态码：response() 丢弃响应体
                    .response()
                    .map(response -> response.status().code())
                    .block(timeout.plusSeconds(1));
            if (status == null) {
                return new Result(null, "no response from the receiver");
            }
            return new Result(status, status >= 200 && status < 300 ? null : "the receiver returned HTTP " + status);
        } catch (RuntimeException e) {
            return new Result(null, reason(e));
        }
    }

    private static String reason(RuntimeException error) {
        Throwable unwrapped = Exceptions.unwrap(error);
        for (Throwable cause = unwrapped; cause != null; cause = cause.getCause()) {
            if (cause instanceof GuardedResolverGroup.BlockedAddressException blocked) {
                log.warn("Webhook 的接收方解析到了不允许的地址 host={}", blocked.host());
                return "the receiver host resolves to an address that is not allowed: " + blocked.host();
            }
            if (cause instanceof TimeoutException || cause instanceof ReadTimeoutException) {
                return "the request timed out";
            }
        }
        String message = unwrapped.getMessage() == null ? unwrapped.getClass().getSimpleName() : unwrapped.getMessage();
        return "the request failed: " + message;
    }

    @Override
    public void close() {
        resolver.close();
    }
}
