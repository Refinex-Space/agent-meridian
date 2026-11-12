package cn.refinex.meridian.runtime.tooling.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.net.AddressPolicy;
import cn.refinex.meridian.engine.spi.port.WebFetchPort;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code web_fetch} 的 SSRF 防护与抓取（T10b-A01）。测试站点是回环地址上的 MockWebServer；测试用的解析器控制主机名解析到哪里，
 * "放行"的策略只额外放行测试站点的地址（{@code 127.0.0.1}），其余仍按 {@link AddressPolicy} 判定。
 */
class GuardedWebFetcherTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final InetAddress TEST_SITE = address("127.0.0.1");

    private final Map<String, Function<Integer, List<InetAddress>>> dns = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> lookups = new ConcurrentHashMap<>();
    private MockWebServer server;
    private GuardedWebFetcher strict;
    private GuardedWebFetcher relaxed;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start(TEST_SITE, 0);
        dns.put("public.test", n -> List.of(TEST_SITE));
        dns.put("localhost", n -> List.of(TEST_SITE));
        dns.put("metadata.test", n -> List.of(address("169.254.169.254")));
        dns.put("private.test", n -> List.of(address("192.168.0.10")));
        dns.put("v6-private.test", n -> List.of(address("fd12:3456::1")));
        dns.put("mixed.test", n -> List.of(address("93.184.216.34"), address("10.0.0.1")));
        strict = new GuardedWebFetcher(
                new MapResolverGroup(), AddressPolicy::isBlocked, WebFetchPort.MAX_RESPONSE_BYTES);
        Predicate<InetAddress> exceptTestSite = a -> !a.equals(TEST_SITE) && AddressPolicy.isBlocked(a);
        relaxed = new GuardedWebFetcher(new MapResolverGroup(), exceptTestSite, WebFetchPort.MAX_RESPONSE_BYTES);
    }

    @AfterEach
    void stop() throws Exception {
        strict.close();
        relaxed.close();
        server.close();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "http://127.0.0.1:{port}/",
                "http://127.1:{port}/",
                "http://2130706433:{port}/",
                "http://0177.0.0.1:{port}/",
                "http://0x7f.0.0.1:{port}/",
                "http://0x7f000001:{port}/",
                "http://[::1]:{port}/",
                "http://[::ffff:127.0.0.1]:{port}/",
                "http://[0:0:0:0:0:ffff:7f00:1]:{port}/",
                "http://10.0.0.1/",
                "http://172.16.0.1/",
                "http://192.168.1.1/",
                "http://169.254.169.254/latest/meta-data/",
                "http://2852039166/latest/meta-data/",
                "http://0251.0376.0251.0376/",
                "http://100.100.100.200/latest/meta-data/",
                "http://[fe80::1]/",
                "http://[fd00:ec2::254]/",
                "http://[fc00::1]/",
                "http://0.0.0.0:{port}/",
                "http://localhost:{port}/",
                "http://metadata.test/",
                "http://private.test/",
                "http://v6-private.test/",
                "http://mixed.test/",
                "http://08.0.0.1/",
                "http://1.2.3.4.5/",
                "ftp://public.test/",
                "file:///etc/passwd",
                "http://user:secret@public.test:{port}/"
            })
    void blockedTargetsAreRejectedBeforeAnyRequestIsSent(String template) {
        URI url = URI.create(template.replace("{port}", String.valueOf(server.getPort())));

        assertThatThrownBy(() -> strict.fetch(url).block(WAIT)).isInstanceOf(ForbiddenException.class);
        assertThat(server.getRequestCount()).as("被拒绝的目标不会收到请求").isZero();
    }

    @Test
    void htmlIsConvertedToMarkdownWithoutScripts() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body("""
                        <html><head><title>平台文档</title><style>body{color:red}</style></head>
                        <body><h1>安装</h1><p>先运行 <code>up.sh</code>，再看<a href="/next">下一页</a>。</p>
                        <script>alert('steal')</script><ul><li>第一步</li><li>第二步</li></ul></body></html>""")
                .build());

        WebFetchPort.Page page = relaxed.fetch(site("/doc")).block(WAIT);

        assertThat(page.contentType()).isEqualTo("text/html");
        assertThat(page.content())
                .startsWith("# 平台文档")
                .contains("安装")
                .contains("`up.sh`")
                .contains("[下一页](http://public.test:" + server.getPort() + "/next)")
                .contains("* 第一步")
                .doesNotContain("alert")
                .doesNotContain("color:red");
        assertThat(page.truncated()).isFalse();
        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeaders().get("User-Agent")).isEqualTo(GuardedWebFetcher.USER_AGENT);
        assertThat(request.getHeaders().get("Cookie")).isNull();
    }

    @Test
    void redirectsAreFollowedHopByHopAndEachHopIsChecked() {
        server.enqueue(redirect("/moved"));
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/plain")
                .body("到了")
                .build());

        WebFetchPort.Page page = relaxed.fetch(site("/start")).block(WAIT);

        assertThat(page.content()).isEqualTo("到了");
        assertThat(page.url()).isEqualTo(site("/start"));
        assertThat(page.finalUrl()).isEqualTo(site("/moved"));
    }

    @ParameterizedTest(name = "重定向到 {0}")
    @ValueSource(
            strings = {
                "http://169.254.169.254/latest/meta-data/iam/",
                "http://private.test/admin",
                "http://[::1]/",
                "http://167772161/",
                "file:///etc/passwd"
            })
    void redirectsToInternalTargetsAreBlocked(String location) {
        server.enqueue(redirect(location));

        assertThatThrownBy(() -> relaxed.fetch(site("/start")).block(WAIT)).isInstanceOf(ForbiddenException.class);
        assertThat(server.getRequestCount()).as("只有第一跳到达了测试站点").isEqualTo(1);
    }

    @Test
    void dnsRebindingIsDefeatedBecauseEveryConnectionResolvesAndChecksAgain() {
        dns.put("rebind.test", n -> n == 1 ? List.of(TEST_SITE) : List.of(address("10.0.0.7")));
        server.enqueue(ok("第一次"));
        server.enqueue(ok("第二次"));
        URI url = URI.create("http://rebind.test:" + server.getPort() + "/");

        assertThat(relaxed.fetch(url).block(WAIT).content()).isEqualTo("第一次");
        assertThatThrownBy(() -> relaxed.fetch(url).block(WAIT))
                .as("第二次解析结果变成了内网地址")
                .isInstanceOf(ForbiddenException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(lookups.get("rebind.test").get()).as("不复用连接，每次都重新解析").isEqualTo(2);
    }

    @Test
    void responseBodyIsCappedAtFiveMegabytes() {
        int size = (int) WebFetchPort.MAX_RESPONSE_BYTES + 512 * 1024;
        Buffer body = new Buffer();
        body.write(new byte[size]);
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/plain")
                .body(body)
                .build());

        WebFetchPort.Page page = relaxed.fetch(site("/big")).block(WAIT);

        assertThat(page.truncated()).isTrue();
        assertThat(page.content()).hasSize((int) WebFetchPort.MAX_RESPONSE_BYTES);
    }

    @Test
    void nonCanonicalPublicAddressesAreRequestedInCanonicalForm() {
        server.enqueue(ok("规范地址"));
        GuardedWebFetcher open = new GuardedWebFetcher(new MapResolverGroup(), a -> false, 1024);
        try {
            URI url = URI.create("http://2130706433:" + server.getPort() + "/x?q=1");
            WebFetchPort.Page page = open.fetch(url).block(WAIT);
            assertThat(page.content()).isEqualTo("规范地址");
            assertThat(page.finalUrl()).isEqualTo(URI.create("http://127.0.0.1:" + server.getPort() + "/x?q=1"));
        } finally {
            open.close();
        }
    }

    @Test
    void bodiesUpToTheLimitAreNotTruncated() {
        server.enqueue(ok("x".repeat(1024)));
        GuardedWebFetcher small = new GuardedWebFetcher(new MapResolverGroup(), a -> false, 1024);
        try {
            assertThat(small.fetch(site("/exact")).block(WAIT).truncated()).isFalse();
        } finally {
            small.close();
        }
    }

    @Test
    void httpErrorsUnsupportedTypesAndRedirectLoopsAreReportedInEnglish() {
        server.enqueue(new MockResponse.Builder().code(404).body("not here").build());
        assertThatThrownBy(() -> relaxed.fetch(site("/missing")).block(WAIT))
                .isInstanceOf(WebFetchException.class)
                .hasMessageContaining("HTTP 404");

        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/png")
                .body("PNG")
                .build());
        assertThatThrownBy(() -> relaxed.fetch(site("/image")).block(WAIT))
                .isInstanceOf(WebFetchException.class)
                .hasMessageContaining("unsupported content type image/png");

        for (int i = 0; i <= GuardedWebFetcher.MAX_REDIRECTS; i++) {
            server.enqueue(redirect("/loop"));
        }
        assertThatThrownBy(() -> relaxed.fetch(site("/loop")).block(WAIT))
                .isInstanceOf(WebFetchException.class)
                .hasMessageContaining("too many redirects");
    }

    private URI site(String path) {
        return URI.create("http://public.test:" + server.getPort() + path);
    }

    private static MockResponse redirect(String location) {
        return new MockResponse.Builder()
                .code(302)
                .addHeader("Location", location)
                .build();
    }

    private static MockResponse ok(String text) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/plain; charset=utf-8")
                .body(text)
                .build();
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(literal, e);
        }
    }

    /** 按测试登记的表解析主机名；同一主机名第 n 次解析可以给出不同结果（模拟 DNS 重绑定）。 */
    private final class MapResolverGroup extends AddressResolverGroup<InetSocketAddress> {

        @Override
        protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
            return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {
                @Override
                protected boolean doIsResolved(InetSocketAddress address) {
                    return !address.isUnresolved();
                }

                @Override
                protected void doResolve(InetSocketAddress unresolved, Promise<InetSocketAddress> promise) {
                    List<InetSocketAddress> all = lookup(unresolved);
                    if (all.isEmpty()) {
                        promise.setFailure(new UnknownHostException(unresolved.getHostString()));
                    } else {
                        promise.setSuccess(all.getFirst());
                    }
                }

                @Override
                protected void doResolveAll(InetSocketAddress unresolved, Promise<List<InetSocketAddress>> promise) {
                    List<InetSocketAddress> all = lookup(unresolved);
                    if (all.isEmpty()) {
                        promise.setFailure(new UnknownHostException(unresolved.getHostString()));
                    } else {
                        promise.setSuccess(all);
                    }
                }
            };
        }

        private List<InetSocketAddress> lookup(InetSocketAddress unresolved) {
            String host = unresolved.getHostString();
            int n = lookups.computeIfAbsent(host, h -> new AtomicInteger()).incrementAndGet();
            Function<Integer, List<InetAddress>> entry = dns.get(host);
            return entry == null
                    ? List.of()
                    : entry.apply(n).stream()
                            .map(a -> new InetSocketAddress(a, unresolved.getPort()))
                            .toList();
        }
    }
}
