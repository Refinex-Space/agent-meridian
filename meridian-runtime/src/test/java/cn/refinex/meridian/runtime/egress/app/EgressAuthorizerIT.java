package cn.refinex.meridian.runtime.egress.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryAgentConfig;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec.SandboxSize;
import cn.refinex.meridian.runtime.egress.domain.EgressDecision;
import cn.refinex.meridian.runtime.egress.domain.EgressDecision.Reason;
import cn.refinex.meridian.runtime.egress.infra.system.SandboxAddressRepository;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 出网授权的判定与缓存（T12b）：真实的 PostgreSQL 与 Redis。两个"节点"各有自己的授权缓存与失效订阅，共享数据库与 Redis；缓存过期用
 * 可控的计时器。经 {@code SandboxService} 改动 Pod IP 的流程（T12b-A02）见 {@code SandboxEgressRevocationIT}。
 */
class EgressAuthorizerIT {

    static final String IP = "10.244.7.21";

    final MutableClock clock = MutableClock.at(Instant.parse("2026-11-08T09:00:00Z"));
    final InMemoryAgentConfig configs = new InMemoryAgentConfig();
    final SandboxAddressRepository addresses = new SandboxAddressRepository(RuntimeTestDatabase.dataSource());
    final AtomicLong nanos = new AtomicLong();
    final Ticker ticker = nanos::get;

    final SimpleMeterRegistry metersA = new SimpleMeterRegistry();
    final EgressGrants grantsA = new EgressGrants(addresses, configs, metersA, ticker);
    final EgressAuthorizer nodeA = new EgressAuthorizer(grantsA, metersA);
    final EgressRevocations revocationsA = new EgressRevocations(grantsA, RuntimeTestRedis.reactiveMessaging());

    final SimpleMeterRegistry metersB = new SimpleMeterRegistry();
    final EgressGrants grantsB = new EgressGrants(addresses, configs, metersB, ticker);
    final EgressAuthorizer nodeB = new EgressAuthorizer(grantsB, metersB);
    final EgressRevocations revocationsB = new EgressRevocations(grantsB, RuntimeTestRedis.reactiveMessaging());

    /** 按 IP 查询是跨租户的：每个用例开始前清空，避免读到其他测试留下的沙箱 IP。 */
    @BeforeEach
    void setUp() {
        RuntimeTestDatabase.truncate();
        long before = subscribers();
        revocationsA.start();
        revocationsB.start();
        awaitSubscribers(before + 2);
    }

    /**
     * 失效频道当前的订阅者数：发布一条不属于任何沙箱的 IP，返回值是收到它的订阅者数（同一 JVM 中缓存的 Spring 上下文也在订阅）。
     *
     * @return 订阅者数
     */
    static long subscribers() {
        Long received = RuntimeTestRedis.reactiveMessaging()
                .notify(EgressRevocations.CHANNEL, "0.0.0.0")
                .block();
        return received == null ? 0 : received;
    }

    /**
     * 等订阅真正建立：订阅是异步的，没建立之前发出的失效通知会丢失。
     *
     * @param expected 至少的订阅者数
     */
    static void awaitSubscribers(long expected) {
        await().atMost(Duration.ofSeconds(5)).until(() -> subscribers() >= expected);
    }

    @AfterEach
    void tearDown() {
        revocationsA.stop();
        revocationsB.stop();
    }

    TestSession sessionWith(List<String> allowlist) {
        EnvironmentSpec environment = new EnvironmentSpec(
                Ids.newId(IdPrefix.ENVIRONMENT), 1, EnvironmentSpec.Tier.SANDBOX, SandboxSize.SMALL, allowlist);
        configs.register(environment);
        return TestSessions.create(
                clock, Ids.newId(IdPrefix.AGENT_VERSION), environment.environmentId(), environment.revision());
    }

    static void assignIp(TestSession session, String ip) {
        DbAssertions.update("UPDATE rt.sessions SET sandbox_pod_ip = ? WHERE id = ?", ip, session.sessionId());
    }

    @Test
    void allowsTargetsOnTheSessionsAllowlistAndDeniesTheRest() {
        TestSession session = sessionWith(List.of("pypi.org", "*.pythonhosted.org"));
        assignIp(session, IP);

        EgressDecision pypi = nodeA.authorize(IP, "pypi.org:443");
        assertThat(pypi.allowed()).isTrue();
        assertThat(pypi.sessionId()).isEqualTo(session.sessionId());
        assertThat(nodeA.authorize(IP, "files.pythonhosted.org:443").allowed()).isTrue();
        assertThat(nodeA.authorize(IP, "PyPI.org").allowed()).as("明文 HTTP 不带端口").isTrue();

        EgressDecision github = nodeA.authorize(IP, "github.com:443");
        assertThat(github.reason()).isEqualTo(Reason.NOT_ALLOWLISTED);
        assertThat(github.sessionId()).isEqualTo(session.sessionId());
        assertThat(nodeA.authorize(IP, "pypi.org.evil.com:443").reason()).isEqualTo(Reason.NOT_ALLOWLISTED);
        assertThat(nodeA.authorize(IP, "pythonhosted.org:443").reason()).isEqualTo(Reason.NOT_ALLOWLISTED);
    }

    @Test
    void anEmptyAllowlistMeansNoEgress() {
        TestSession session = sessionWith(List.of());
        assignIp(session, IP);

        assertThat(nodeA.authorize(IP, "pypi.org:443").reason()).isEqualTo(Reason.NOT_ALLOWLISTED);
    }

    @Test
    void unknownSourcesAndMalformedInputAreDenied() {
        TestSession session = sessionWith(List.of("pypi.org"));
        assignIp(session, IP);

        assertThat(nodeA.authorize("10.244.7.22", "pypi.org:443").reason()).isEqualTo(Reason.UNKNOWN_SOURCE);
        assertThat(nodeA.authorize("not-an-ip", "pypi.org:443").reason()).isEqualTo(Reason.UNKNOWN_SOURCE);
        assertThat(nodeA.authorize(null, "pypi.org:443").reason()).isEqualTo(Reason.UNKNOWN_SOURCE);
        assertThat(nodeA.authorize(IP, "user@pypi.org:443").reason()).isEqualTo(Reason.INVALID_TARGET);
        assertThat(nodeA.authorize(IP, null).reason()).isEqualTo(Reason.INVALID_TARGET);
    }

    @Test
    void terminatedSessionsHaveNoEgress() {
        TestSession session = sessionWith(List.of("pypi.org"));
        assignIp(session, IP);
        DbAssertions.update("UPDATE rt.sessions SET status = 'terminated' WHERE id = ?", session.sessionId());

        assertThat(nodeA.authorize(IP, "pypi.org:443").reason()).isEqualTo(Reason.UNKNOWN_SOURCE);
    }

    @Test
    void twoSessionsRecordingTheSameIpAreDenied() {
        TestSession stale = sessionWith(List.of("pypi.org"));
        TestSession current = sessionWith(List.of("pypi.org"));
        assignIp(stale, IP);
        assignIp(current, IP);

        assertThat(nodeA.authorize(IP, "pypi.org:443").reason())
                .as("分不清是哪个会话的 Pod，一律拒绝")
                .isEqualTo(Reason.UNKNOWN_SOURCE);
    }

    @Test
    void grantsAreCachedForThirtySeconds() {
        TestSession first = sessionWith(List.of("pypi.org"));
        TestSession second = sessionWith(List.of("github.com"));
        assignIp(first, IP);
        assertThat(nodeA.authorize(IP, "pypi.org:443").allowed()).isTrue();

        // 绕过平台直接改库（没有失效通知）：缓存期内仍是旧授权，过期后重新查询
        assignIp(first, null);
        assignIp(second, IP);
        nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(nodeA.authorize(IP, "pypi.org:443").allowed()).isTrue();
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(nodeA.authorize(IP, "pypi.org:443").reason()).isEqualTo(Reason.NOT_ALLOWLISTED);
        assertThat(nodeA.authorize(IP, "github.com:443").allowed()).isTrue();

        assertThat(metersA.get("cache.gets")
                        .tag("cache", EgressGrants.CACHE_NAME)
                        .tag("result", "hit")
                        .functionCounter()
                        .count())
                .isEqualTo(2);
        assertThat(metersA.get("cache.gets")
                        .tag("cache", EgressGrants.CACHE_NAME)
                        .tag("result", "miss")
                        .functionCounter()
                        .count())
                .isEqualTo(2);
    }

    @Test
    void unknownSourcesAreNotCached() {
        TestSession session = sessionWith(List.of("pypi.org"));

        assertThat(nodeA.authorize(IP, "pypi.org:443").reason()).isEqualTo(Reason.UNKNOWN_SOURCE);
        assignIp(session, IP);
        assertThat(nodeA.authorize(IP, "pypi.org:443").allowed())
                .as("新认领的沙箱立即可用")
                .isTrue();
    }

    @Test
    void revokingAnIpEvictsItOnEveryNode() {
        TestSession first = sessionWith(List.of("pypi.org"));
        TestSession second = sessionWith(List.of("github.com"));
        assignIp(first, IP);
        assertThat(nodeA.authorize(IP, "pypi.org:443").allowed()).isTrue();
        assertThat(nodeB.authorize(IP, "pypi.org:443").allowed()).isTrue();

        // 节点 A 把 IP 改给另一个会话并广播（没有事务时立即广播）
        assignIp(first, null);
        assignIp(second, IP);
        revocationsA.podIpChanged(null, IP);

        assertThat(nodeA.authorize(IP, "pypi.org:443").reason()).isEqualTo(Reason.NOT_ALLOWLISTED);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(nodeB.authorize(IP, "pypi.org:443").reason())
                                .as("其他节点经 Pub/Sub 淘汰，不等 30 秒过期")
                                .isEqualTo(Reason.NOT_ALLOWLISTED));
        assertThat(nodeB.authorize(IP, "github.com:443").allowed()).isTrue();
    }

    @Test
    void authorizationLatencyIsRecordedPerResult() {
        TestSession session = sessionWith(List.of("pypi.org"));
        assignIp(session, IP);

        nodeA.authorize(IP, "pypi.org:443");
        nodeA.authorize(IP, "github.com:443");
        nodeA.authorize(IP, "github.com:443");

        assertThat(metersA.get(EgressAuthorizer.AUTHORIZE_METRIC)
                        .tag("result", "allowed")
                        .tag("reason", "allowed")
                        .timer()
                        .count())
                .isEqualTo(1);
        assertThat(metersA.get(EgressAuthorizer.AUTHORIZE_METRIC)
                        .tag("result", "denied")
                        .tag("reason", "not_allowlisted")
                        .timer()
                        .count())
                .isEqualTo(2);
    }
}
