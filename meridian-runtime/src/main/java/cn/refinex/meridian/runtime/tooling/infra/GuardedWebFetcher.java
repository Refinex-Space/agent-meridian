package cn.refinex.meridian.runtime.tooling.infra;

import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.engine.spi.port.WebFetchPort;
import cn.refinex.meridian.runtime.tooling.domain.AddressPolicy;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.HttpResources;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * {@link WebFetchPort} 的实现：带 SSRF 防护的网页抓取（架构设计「平台工具」「智能体安全」，ADR-0035）。
 *
 * <ul>
 *   <li>每一跳（含重定向）发请求之前校验 URL：只允许 http 与 https；主机是 IP 字面量（含十进制、八进制等写法）时按
 *       {@link AddressPolicy} 判定，只由数字组成却不是合法地址的主机名直接拒绝。
 *   <li>主机名经 {@link GuardedResolverGroup} 解析，解析结果有被拒绝的地址就不建立连接；不复用连接，每一跳都重新解析、重新校验。
 *   <li>不自动跟随重定向，自己逐跳处理，至多 {@value #MAX_REDIRECTS} 跳。
 *   <li>响应体至多读取 {@link WebFetchPort#MAX_RESPONSE_BYTES}（5 MB），超出部分丢弃并标记截断。
 *   <li>HTML 转 Markdown，文本与 JSON 原样；其他内容类型拒绝（{@link WebContentConverter}）。
 * </ul>
 *
 * <p>不带 Cookie 与凭据，不走代理（代理会替客户端解析主机名，使解析后的校验失效）。被拒绝时以 {@link ForbiddenException} 失败，
 * 其他失败以 {@link WebFetchException}（英文说明，交给模型）失败。
 *
 * <p>线程安全：不可变，可并发调用；{@link #close()} 释放 DNS 解析器。
 */
public final class GuardedWebFetcher implements WebFetchPort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GuardedWebFetcher.class);

    /** 最多跟随的重定向次数。 */
    static final int MAX_REDIRECTS = 5;

    /** 建立连接的超时。 */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 每一跳等待响应的超时。 */
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    /** 一次抓取（含全部重定向与读取响应体）的总超时，小于 {@code web_fetch} 工具的等待上限。 */
    static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(60);

    /** 请求的 {@code User-Agent}。 */
    static final String USER_AGENT = "AgentMeridian-WebFetch/1.0";

    private final HttpClient http;
    private final AddressResolverGroup<InetSocketAddress> resolver;
    private final Predicate<InetAddress> blocked;
    private final long maxBytes;
    private final WebContentConverter converter = new WebContentConverter();

    /** 使用系统 DNS 与平台地址策略。 */
    public GuardedWebFetcher() {
        this(systemDns(), AddressPolicy::isBlocked, MAX_RESPONSE_BYTES);
    }

    /**
     * 指定解析器与地址策略，测试用：测试服务在回环地址上，测试替换"被拒绝"的判定与主机名解析。
     *
     * @param dns 实际做 DNS 解析的解析器组
     * @param blocked 地址是否被拒绝
     * @param maxBytes 响应体上限
     */
    GuardedWebFetcher(AddressResolverGroup<InetSocketAddress> dns, Predicate<InetAddress> blocked, long maxBytes) {
        this.blocked = Objects.requireNonNull(blocked, "blocked");
        this.resolver = new GuardedResolverGroup(dns, blocked);
        this.maxBytes = maxBytes;
        this.http = HttpClient.create(ConnectionProvider.newConnection())
                // 与 DNS 解析器的 NIO 数据报通道一致，不用本机原生传输
                .runOn(HttpResources.get(), false)
                .resolver(resolver)
                .noProxy()
                .followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .responseTimeout(RESPONSE_TIMEOUT)
                .compress(true)
                .headers(headers -> headers.set(HttpHeaderNames.USER_AGENT, USER_AGENT)
                        .set(HttpHeaderNames.ACCEPT, "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5"));
    }

    @Override
    public Mono<Page> fetch(URI url) {
        Objects.requireNonNull(url, "url");
        return Mono.defer(() -> hop(url, url, 0)).timeout(TOTAL_TIMEOUT).onErrorMap(GuardedWebFetcher::translate);
    }

    /** 一跳：校验、请求；重定向时校验下一跳并继续。 */
    private Mono<Page> hop(URI original, URI requested, int redirects) {
        URI current = check(requested);
        return http.get()
                .uri(current)
                .responseConnection((response, connection) -> {
                    int status = response.status().code();
                    if (status >= 300
                            && status < 400
                            && response.responseHeaders().contains(HttpHeaderNames.LOCATION)) {
                        String location = response.responseHeaders().get(HttpHeaderNames.LOCATION);
                        connection.dispose();
                        return Mono.just(new Hop.Redirect(current.resolve(location.strip())));
                    }
                    if (status >= 400) {
                        connection.dispose();
                        return Mono.error(
                                new WebFetchException("the server returned HTTP " + status + " for " + current));
                    }
                    String contentType = response.responseHeaders().get(HttpHeaderNames.CONTENT_TYPE);
                    String mediaType = WebContentConverter.mediaType(contentType);
                    if (!WebContentConverter.supported(mediaType)) {
                        connection.dispose();
                        return Mono.error(new WebFetchException("unsupported content type " + mediaType
                                + "; only HTML, plain text, JSON and XML can be fetched"));
                    }
                    return read(connection.inbound().receive().asByteArray())
                            .map(body -> (Hop) new Hop.Body(contentType == null ? mediaType : contentType, body));
                })
                .next()
                .switchIfEmpty(Mono.error(new WebFetchException("no response from " + current)))
                .flatMap(result -> switch (result) {
                    case Hop.Redirect redirect -> {
                        if (redirects >= MAX_REDIRECTS) {
                            yield Mono.error(
                                    new WebFetchException("too many redirects (more than " + MAX_REDIRECTS + ")"));
                        }
                        yield hop(original, redirect.location(), redirects + 1);
                    }
                    case Hop.Body body ->
                        Mono.fromCallable(() -> new Page(
                                        original,
                                        current,
                                        WebContentConverter.mediaType(body.contentType()),
                                        converter.convert(
                                                body.contentType(), body.bytes().bytes(), current.toString()),
                                        body.bytes().truncated()))
                                .subscribeOn(Schedulers.boundedElastic());
                });
    }

    /** 读取响应体，至多 {@link #maxBytes} 字节；超出时停止读取并标记截断。 */
    private Mono<Bytes> read(Flux<byte[]> chunks) {
        return Mono.defer(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean[] truncated = {false};
            return chunks.takeUntil(chunk -> {
                        long remaining = maxBytes - out.size();
                        int take = (int) Math.min(remaining, chunk.length);
                        out.write(chunk, 0, take);
                        if (chunk.length > take) {
                            truncated[0] = true;
                        }
                        return truncated[0];
                    })
                    .then(Mono.fromSupplier(() -> new Bytes(out.toByteArray(), truncated[0])));
        });
    }

    /**
     * 发请求之前校验 URL：协议、主机，IP 字面量按地址策略判定。{@code java.net.URI} 对 {@code 127.1} 这类主机给不出
     * {@code getHost()}，这时从 authority 中取主机；非标准写法的 IP（十进制、八进制等）改写为规范形式再请求，HTTP 客户端不会再把它当作
     * 主机名去解析。
     *
     * @param url 本跳的地址
     * @return 要请求的地址
     * @throws ForbiddenException 被拒绝时
     */
    private URI check(URI url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ForbiddenException("web_fetch 只允许 http 与 https：" + url);
        }
        if (url.getRawUserInfo() != null
                || (url.getRawAuthority() != null && url.getRawAuthority().contains("@"))) {
            throw new ForbiddenException("web_fetch 不允许在地址中携带用户信息");
        }
        String host = host(url);
        Optional<InetAddress> literal;
        try {
            literal = AddressPolicy.literal(host);
        } catch (IllegalArgumentException e) {
            log.warn("web_fetch 拒绝了不合法的数字主机名 host={}", host);
            throw new ForbiddenException("web_fetch 拒绝了不合法的数字主机名：" + host);
        }
        if (literal.isEmpty()) {
            if (url.getHost() == null) {
                throw new WebFetchException("the URL has an invalid host: " + url);
            }
            return url;
        }
        if (blocked.test(literal.get())) {
            log.warn("web_fetch 拒绝了内网或保留地址 host={}", host);
            throw new ForbiddenException("web_fetch 拒绝了内网或保留地址：" + host);
        }
        return canonical(url, literal.get());
    }

    /** URL 的主机部分；{@code getHost()} 为空时从 authority 中去掉端口取得。 */
    private static String host(URI url) {
        if (url.getHost() != null) {
            return url.getHost();
        }
        String authority = url.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            throw new WebFetchException("the URL has no host: " + url);
        }
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            return end < 0 ? authority : authority.substring(0, end + 1);
        }
        int colon = authority.lastIndexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }

    /** 把主机换成 IP 的规范写法。 */
    private static URI canonical(URI url, InetAddress address) {
        String host = address.getHostAddress();
        if (host.contains(":")) {
            host = "[" + host + "]";
        }
        String authority = host + (url.getPort() < 0 ? portOf(url) : ":" + url.getPort());
        return URI.create(url.getScheme() + "://" + authority
                + (url.getRawPath() == null ? "" : url.getRawPath())
                + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery()));
    }

    /** {@code getHost()} 为空时 {@code getPort()} 也是 -1，端口从 authority 中取。 */
    private static String portOf(URI url) {
        String authority = url.getRawAuthority();
        if (authority == null || authority.endsWith("]")) {
            return "";
        }
        int colon = authority.lastIndexOf(':');
        return colon < 0 ? "" : authority.substring(colon);
    }

    /** 把底层错误翻译为平台异常：被拒绝的解析结果为 {@link ForbiddenException}，其余为 {@link WebFetchException}。 */
    private static Throwable translate(Throwable error) {
        Throwable unwrapped = Exceptions.unwrap(error);
        if (unwrapped instanceof ForbiddenException || unwrapped instanceof WebFetchException) {
            return unwrapped;
        }
        for (Throwable cause = unwrapped; cause != null; cause = cause.getCause()) {
            if (cause instanceof GuardedResolverGroup.BlockedAddressException blockedAddress) {
                log.warn("web_fetch 拒绝了解析到内网或保留地址的主机 host={}", blockedAddress.host());
                return new ForbiddenException("web_fetch 拒绝了解析到内网或保留地址的主机：" + blockedAddress.host());
            }
        }
        if (unwrapped instanceof TimeoutException) {
            return new WebFetchException("the request timed out", unwrapped);
        }
        String reason = unwrapped.getMessage() == null ? unwrapped.getClass().getSimpleName() : unwrapped.getMessage();
        return new WebFetchException("the request failed: " + reason, unwrapped);
    }

    /** 系统 DNS：非阻塞的 Netty 解析器，名字服务器取自操作系统配置。 */
    private static AddressResolverGroup<InetSocketAddress> systemDns() {
        return new DnsAddressResolverGroup(new DnsNameResolverBuilder()
                .datagramChannelType(NioDatagramChannel.class)
                .socketChannelType(NioSocketChannel.class)
                .nameServerProvider(DnsServerAddressStreamProviders.platformDefault()));
    }

    @Override
    public void close() {
        resolver.close();
    }

    /** 一跳的结果。 */
    private sealed interface Hop {

        /** 重定向到下一跳。 */
        record Redirect(URI location) implements Hop {}

        /** 读到的响应体。 */
        record Body(String contentType, Bytes bytes) implements Hop {}
    }

    /** 响应体与是否被截断。 */
    private record Bytes(byte[] bytes, boolean truncated) {}
}
