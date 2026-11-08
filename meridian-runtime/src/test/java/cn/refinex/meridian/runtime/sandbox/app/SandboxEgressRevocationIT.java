package cn.refinex.meridian.runtime.sandbox.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryAgentConfig;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec.SandboxSize;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.egress.app.EgressAuthorizer;
import cn.refinex.meridian.runtime.egress.app.EgressGrants;
import cn.refinex.meridian.runtime.egress.app.EgressRevocations;
import cn.refinex.meridian.runtime.egress.domain.EgressDecision;
import cn.refinex.meridian.runtime.egress.domain.EgressDecision.Reason;
import cn.refinex.meridian.runtime.egress.infra.system.SandboxAddressRepository;
import cn.refinex.meridian.runtime.sandbox.app.SandboxService.ReclaimOutcome;
import cn.refinex.meridian.runtime.sandbox.infra.SandboxOutputRepository;
import cn.refinex.meridian.runtime.sandbox.infra.SessionSandboxRepository;
import cn.refinex.meridian.runtime.sandbox.infra.system.IdleSandboxRepository;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.RuntimeTestObjectStore;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.util.unit.DataSize;

/**
 * 沙箱的 Pod IP 被清空或改变后，30 秒缓存内的出网授权立即失效（T12b-A02）：经真实的 {@link SandboxService}（回收、删除、重新连接、
 * 新认领）改动 Pod IP，两个"节点"各有自己的授权缓存，节点 A 执行沙箱操作，节点 B 只经 Redis 收到失效通知。整个用例在 30 秒的缓存期
 * 内完成，不推进缓存的计时器。真实的 PostgreSQL、Redis 与 MinIO，内存中的沙箱驱动。
 */
class SandboxEgressRevocationIT {

    static final Instant START = Instant.parse("2026-11-08T09:00:00Z");
    static final String SHARED_IP = "10.244.9.9";

    final DataSource dataSource = RuntimeTestDatabase.dataSource();
    final ObjectStore objects = RuntimeTestObjectStore.get();
    final MutableClock clock = MutableClock.at(START);
    final FakeSandboxDriver driver = new FakeSandboxDriver(objects);
    final InMemoryAgentConfig configs = new InMemoryAgentConfig();
    final SandboxAddressRepository addresses = new SandboxAddressRepository(dataSource);

    final SimpleMeterRegistry metersA = new SimpleMeterRegistry();
    final EgressGrants grantsA = new EgressGrants(addresses, configs, metersA);
    final EgressAuthorizer nodeA = new EgressAuthorizer(grantsA, metersA);
    final EgressRevocations revocationsA = new EgressRevocations(grantsA, RuntimeTestRedis.reactiveMessaging());

    final SimpleMeterRegistry metersB = new SimpleMeterRegistry();
    final EgressGrants grantsB = new EgressGrants(addresses, configs, metersB);
    final EgressAuthorizer nodeB = new EgressAuthorizer(grantsB, metersB);
    final EgressRevocations revocationsB = new EgressRevocations(grantsB, RuntimeTestRedis.reactiveMessaging());

    final SandboxService service = new SandboxService(
            new StaticListableBeanFactory(Map.of("driver", driver)).getBeanProvider(SandboxDriver.class),
            configs,
            new SessionSandboxRepository(dataSource),
            new SandboxOutputRepository(dataSource),
            objects,
            new OutboxWriter(dataSource, "rt", clock),
            RuntimeTestDatabase.transactions(),
            SandboxServiceIT.properties(DataSize.ofMegabytes(64)),
            revocationsA,
            metersA,
            clock);
    final IdleSandboxReaper reaper = new IdleSandboxReaper(
            new IdleSandboxRepository(dataSource),
            service,
            SandboxServiceIT.properties(DataSize.ofMegabytes(64)),
            clock);

    /** 按 IP 查询与回收器都跨租户：每个用例开始前清空。 */
    @BeforeEach
    void setUp() {
        RuntimeTestDatabase.truncate();
        long before = subscribers();
        revocationsA.start();
        revocationsB.start();
        awaitSubscribers(before + 2);
    }

    @AfterEach
    void tearDown() {
        revocationsA.stop();
        revocationsB.stop();
    }

    @Test
    void reclaimingTheSandboxRevokesCachedGrantsOnEveryNodeImmediately() {
        Sandboxed s = sandboxed(List.of("pypi.org"));
        assertAllowed(nodeA, s, "pypi.org:443");
        assertAllowed(nodeB, s, "pypi.org:443");

        clock.advance(Duration.ofMinutes(11));
        assertThat(reaper.reapOnce()).containsEntry(ReclaimOutcome.RECLAIMED, 1);

        assertThat(nodeA.authorize(s.ip(), "pypi.org:443").reason())
                .as("执行回收的节点在提交后立即淘汰")
                .isEqualTo(Reason.UNKNOWN_SOURCE);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(nodeB.authorize(s.ip(), "pypi.org:443").reason())
                                .as("其他节点经 Pub/Sub 淘汰，不等 30 秒过期")
                                .isEqualTo(Reason.UNKNOWN_SOURCE));
    }

    @Test
    void deletingTheSessionsSandboxRevokesItsGrant() {
        Sandboxed s = sandboxed(List.of("pypi.org"));
        assertAllowed(nodeA, s, "pypi.org:443");

        PlatformContexts.run(
                s.session().context(),
                () -> service.deleteSessionSandbox(s.session().sessionId()));

        assertThat(nodeA.authorize(s.ip(), "pypi.org:443").reason()).isEqualTo(Reason.UNKNOWN_SOURCE);
    }

    @Test
    void aNewPodReusingTheIpDoesNotInheritTheOldGrantEvenOnANodeThatMissedTheRevocation() {
        driver.nextPodIp(SHARED_IP);
        Sandboxed old = sandboxed(List.of("pypi.org"));
        assertThat(old.ip()).isEqualTo(SHARED_IP);
        assertAllowed(nodeA, old, "pypi.org:443");
        assertAllowed(nodeB, old, "pypi.org:443");

        // 节点 B 断开订阅期间回收旧沙箱：B 错过这次失效通知，仍缓存着旧会话的授权
        revocationsB.stop();
        clock.advance(Duration.ofMinutes(11));
        assertThat(reaper.reapOnce()).containsEntry(ReclaimOutcome.RECLAIMED, 1);
        long before = subscribers();
        revocationsB.start();
        awaitSubscribers(before + 1);

        // 另一个租户的新会话认领到同一个 IP：认领时再次淘汰这个 IP
        driver.nextPodIp(SHARED_IP);
        Sandboxed next = sandboxed(List.of("github.com"));
        assertThat(next.ip()).isEqualTo(SHARED_IP);

        for (EgressAuthorizer node : List.of(nodeA, nodeB)) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                EgressDecision decision = node.authorize(SHARED_IP, "pypi.org:443");
                assertThat(decision.reason()).isEqualTo(Reason.NOT_ALLOWLISTED);
                assertThat(decision.sessionId()).isEqualTo(next.session().sessionId());
            });
            assertAllowed(node, next, "github.com:443");
        }
    }

    @Test
    void reconnectingToASandboxWhosePodGotANewIpRevokesTheOldIp() {
        Sandboxed s = sandboxed(List.of("pypi.org"));
        assertAllowed(nodeA, s, "pypi.org:443");
        assertAllowed(nodeB, s, "pypi.org:443");

        driver.changePodIp(s.handle(), "10.244.3.3");
        clock.advance(Duration.ofMinutes(1));
        SandboxHandle resumed = prepare(s);
        assertThat(resumed.podIp()).isEqualTo("10.244.3.3");

        assertThat(nodeA.authorize(s.ip(), "pypi.org:443").reason()).isEqualTo(Reason.UNKNOWN_SOURCE);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(nodeB.authorize(s.ip(), "pypi.org:443").reason())
                                .isEqualTo(Reason.UNKNOWN_SOURCE));
        assertThat(nodeA.authorize("10.244.3.3", "pypi.org:443").allowed()).isTrue();
    }

    /** 一个已经取得沙箱的会话。 */
    record Sandboxed(TestSession session, EnvironmentSpec environment, String agentVersionId, SandboxHandle handle) {

        String ip() {
            return handle.podIp();
        }
    }

    Sandboxed sandboxed(List<String> allowlist) {
        String agentVersionId = Ids.newId(IdPrefix.AGENT_VERSION);
        EnvironmentSpec environment = new EnvironmentSpec(
                Ids.newId(IdPrefix.ENVIRONMENT), 1, EnvironmentSpec.Tier.SANDBOX, SandboxSize.SMALL, allowlist);
        configs.register(TestSpecs.agent().agentVersionId(agentVersionId).build())
                .register(environment);
        TestSession session =
                TestSessions.create(clock, agentVersionId, environment.environmentId(), environment.revision());
        Sandboxed sandboxed = new Sandboxed(session, environment, agentVersionId, null);
        return new Sandboxed(session, environment, agentVersionId, prepare(sandboxed));
    }

    SandboxHandle prepare(Sandboxed s) {
        PlatformContext platform = s.session().context().toBuilder()
                .threadId(s.session().mainThreadId())
                .turnId(Ids.newId(IdPrefix.TURN))
                .leaseEpoch(1L)
                .interaction(Interaction.ATTENDED)
                .build();
        TurnContext turn = new TurnContext(
                platform,
                "u_1024",
                s.agentVersionId(),
                s.environment().environmentId(),
                s.environment().revision(),
                new PermissionPolicy(PermissionMode.DEFAULT, List.of()),
                null,
                null,
                null);
        return PlatformContexts.call(platform, () -> service.prepare(turn)).orElseThrow();
    }

    static void assertAllowed(EgressAuthorizer node, Sandboxed s, String target) {
        EgressDecision decision = node.authorize(s.ip(), target);
        assertThat(decision.allowed()).as("%s 访问 %s", s.ip(), target).isTrue();
        assertThat(decision.sessionId()).isEqualTo(s.session().sessionId());
    }

    static long subscribers() {
        Long received = RuntimeTestRedis.reactiveMessaging()
                .notify(EgressRevocations.CHANNEL, "0.0.0.0")
                .block();
        return received == null ? 0 : received;
    }

    static void awaitSubscribers(long expected) {
        await().atMost(Duration.ofSeconds(5)).until(() -> subscribers() >= expected);
    }
}
