package cn.refinex.meridian.runtime.sandbox.app;

import static org.assertj.core.api.Assertions.assertThat;

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
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.engine.spi.spec.SkillRef;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
import cn.refinex.meridian.runtime.egress.app.EgressGrants;
import cn.refinex.meridian.runtime.egress.app.EgressRevocations;
import cn.refinex.meridian.runtime.egress.infra.system.SandboxAddressRepository;
import cn.refinex.meridian.runtime.sandbox.app.SandboxService.ReclaimOutcome;
import cn.refinex.meridian.runtime.sandbox.infra.SandboxOutputRepository;
import cn.refinex.meridian.runtime.sandbox.infra.SessionSandboxRepository;
import cn.refinex.meridian.runtime.sandbox.infra.system.IdleSandboxRepository;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.RuntimeTestObjectStore;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import cn.refinex.meridian.runtime.support.TestNode;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.util.unit.DataSize;

/**
 * {@link SandboxService} 的生命周期（T12a-A02 的状态机与竞争部分、A06 的同步部分、A07 的计量部分）：真实的 PostgreSQL 与 MinIO、可控时钟、
 * 内存中的沙箱驱动。真实沙箱上的同一流程由 {@code SandboxSessionKindIT} 验证。
 */
class SandboxServiceIT {

    static final Instant START = Instant.parse("2026-11-08T09:00:00Z");

    final DataSource dataSource = RuntimeTestDatabase.dataSource();
    final ObjectStore objects = RuntimeTestObjectStore.get();
    final MutableClock clock = MutableClock.at(START);
    final FakeSandboxDriver driver = new FakeSandboxDriver(objects);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final InMemoryAgentConfig configs = new InMemoryAgentConfig();
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    final String agentVersionId = Ids.newId(IdPrefix.AGENT_VERSION);
    final SkillDefinition skill = new SkillDefinition(
            "skl_docx", 1, "docx", Map.of("SKILL.md", "---\nname: docx\n---\n", "scripts/x.py", "print(1)"));

    /** 回收器跨租户扫描：每个用例开始前清空，避免读到其他用例与测试类留下的沙箱。 */
    @BeforeEach
    void clean() {
        RuntimeTestDatabase.truncate();
    }

    @AfterEach
    void shutDown() {
        executor.shutdownNow();
    }

    /** 启用、空闲阈值 10 分钟、单个操作等待 30 秒的配置。 */
    static SandboxProperties properties(DataSize maxOutput) {
        SandboxProperties d = SandboxProperties.defaults();
        return new SandboxProperties(
                true,
                null,
                d.namespace(),
                d.warmPoolPrefix(),
                d.connection(),
                null,
                null,
                null,
                "http",
                d.readyTimeout(),
                d.execTimeout(),
                Duration.ofMinutes(10),
                d.reapInterval(),
                d.reapBatchSize(),
                Duration.ofSeconds(30),
                d.bashDefaultTimeout(),
                d.bashMaxTimeout(),
                maxOutput);
    }

    SandboxService service(DataSize maxOutput) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("driver", driver));
        return new SandboxService(
                beans.getBeanProvider(SandboxDriver.class),
                configs,
                new SessionSandboxRepository(dataSource),
                new SandboxOutputRepository(dataSource),
                objects,
                new OutboxWriter(dataSource, "rt", clock),
                RuntimeTestDatabase.transactions(),
                properties(maxOutput),
                new EgressRevocations(
                        new EgressGrants(new SandboxAddressRepository(dataSource), configs, meters),
                        RuntimeTestRedis.reactiveMessaging()),
                meters,
                clock);
    }

    SandboxService service() {
        return service(DataSize.ofMegabytes(64));
    }

    IdleSandboxReaper reaper(SandboxService service) {
        return new IdleSandboxReaper(
                new IdleSandboxRepository(dataSource), service, properties(DataSize.ofMegabytes(64)), clock);
    }

    TestSession session(EnvironmentSpec environment) {
        ResolvedAgentSpec agent = TestSpecs.agent()
                .agentVersionId(agentVersionId)
                .skill(new SkillRef("skl_docx", 1, "docx"))
                .build();
        configs.register(agent).register(environment).register(skill);
        return TestSessions.create(clock, agentVersionId, environment.environmentId(), environment.revision());
    }

    static EnvironmentSpec sandbox(SandboxSize size) {
        return new EnvironmentSpec(Ids.newId(IdPrefix.ENVIRONMENT), 1, EnvironmentSpec.Tier.SANDBOX, size, List.of());
    }

    TurnContext turn(TestSession session, EnvironmentSpec environment) {
        PlatformContext platform = PlatformContext.builder()
                .tenantId(session.tenantId())
                .projectId(session.projectId())
                .sessionId(session.sessionId())
                .threadId(session.mainThreadId())
                .turnId(Ids.newId(IdPrefix.TURN))
                .leaseEpoch(1L)
                .interaction(Interaction.ATTENDED)
                .build();
        return new TurnContext(
                platform,
                "u_1024",
                agentVersionId,
                environment.environmentId(),
                environment.revision(),
                new PermissionPolicy(PermissionMode.DEFAULT, List.of()),
                null,
                null,
                null);
    }

    static Optional<SandboxHandle> prepare(SandboxService service, TurnContext turn) {
        return PlatformContexts.call(turn.platform(), () -> service.prepare(turn));
    }

    static void afterTurn(SandboxService service, TurnContext turn, SandboxHandle handle) {
        PlatformContexts.run(turn.platform(), () -> service.afterTurn(turn, handle, true));
    }

    static Map<String, Object> sessionRow(TestSession session) {
        return DbAssertions.row(
                "SELECT sandbox_state::text AS state, sandbox_pod_ip, sandbox_last_used_at, sandbox_snapshot_key, "
                        + "sandbox_metered_at FROM rt.sessions WHERE id = ?",
                session.sessionId());
    }

    @Test
    void theFirstSandboxTurnClaimsASandboxAndLaterTurnsReconnectToIt() {
        SandboxService service = service();
        EnvironmentSpec environment = sandbox(SandboxSize.SMALL);
        TestSession session = session(environment);

        SandboxHandle first = prepare(service, turn(session, environment)).orElseThrow();
        clock.advance(Duration.ofMinutes(1));
        SandboxHandle second = prepare(service, turn(session, environment)).orElseThrow();

        assertThat(second).isEqualTo(first);
        assertThat(driver.calls).containsExactly("create", "resume");
        Map<String, Object> row = sessionRow(session);
        assertThat((String) row.get("state"))
                .contains("\"sandbox_id\": \"" + first.sandboxId() + "\"")
                .contains("\"size\": \"small\"")
                .contains("\"state\": \"" + first.state() + "\"");
        assertThat(row.get("sandbox_pod_ip")).isEqualTo(first.podIp());
        assertThat(DbAssertions.instant(row.get("sandbox_last_used_at"))).isEqualTo(START.plusSeconds(60));
        assertThat(DbAssertions.instant(row.get("sandbox_metered_at")))
                .as("计量起点是认领时间")
                .isEqualTo(START);
        assertThat(meters.timer(SandboxService.CLAIM_METRIC).count()).isEqualTo(1);
        assertThat(driver.skillSyncs)
                .hasSize(2)
                .allSatisfy(skills -> assertThat(skills).containsExactly(skill));
    }

    @Test
    void noneTierTurnsDoNotTouchTheSandbox() {
        SandboxService service = service();
        EnvironmentSpec none =
                new EnvironmentSpec(Ids.newId(IdPrefix.ENVIRONMENT), 1, EnvironmentSpec.Tier.NONE, null, List.of());
        TestSession session = session(none);

        assertThat(prepare(service, turn(session, none))).isEmpty();
        assertThat(driver.calls).isEmpty();
    }

    @Test
    void anIdleSandboxIsSnapshottedAndDeletedAndTheNextTurnRestoresItsFiles() {
        SandboxService service = service();
        IdleSandboxReaper reaper = reaper(service);
        EnvironmentSpec environment = sandbox(SandboxSize.SMALL);
        TestSession session = session(environment);
        SandboxHandle first = prepare(service, turn(session, environment)).orElseThrow();
        driver.box(first).outputs().put("a.txt", "第一轮".getBytes(StandardCharsets.UTF_8));

        clock.advance(Duration.ofMinutes(9));
        assertThat(reaper.reapOnce()).as("不到 10 分钟不回收").isEmpty();
        clock.advance(Duration.ofMinutes(2));
        assertThat(reaper.reapOnce()).containsEntry(ReclaimOutcome.RECLAIMED, 1);

        assertThat(driver.live).isEmpty();
        Map<String, Object> reclaimed = sessionRow(session);
        assertThat(reclaimed.get("state")).isNull();
        assertThat(reclaimed.get("sandbox_pod_ip")).as("清空 Pod IP，出网代理不再认它").isNull();
        String firstSnapshot = (String) reclaimed.get("sandbox_snapshot_key");
        assertThat(firstSnapshot)
                .startsWith("rt/snapshots/" + session.tenantId() + "/" + session.projectId() + "/" + session.sessionId()
                        + "/");

        SandboxHandle restored = prepare(service, turn(session, environment)).orElseThrow();
        assertThat(restored.sandboxId()).isNotEqualTo(first.sandboxId());
        assertThat(driver.calls).containsSubsequence("create", "snapshot", "delete", "restore");
        assertThat(new String(driver.box(restored).outputs().get("a.txt"), StandardCharsets.UTF_8))
                .isEqualTo("第一轮");
        assertThat(meters.timer(SandboxService.CLAIM_METRIC).count())
                .as("恢复不计入认领耗时")
                .isEqualTo(1);

        clock.advance(Duration.ofMinutes(11));
        assertThat(reaper.reapOnce()).containsEntry(ReclaimOutcome.RECLAIMED, 1);
        String secondSnapshot = (String) sessionRow(session).get("sandbox_snapshot_key");
        assertThat(secondSnapshot).isNotEqualTo(firstSnapshot);
        assertThat(objects.exists(secondSnapshot)).isTrue();
        assertThat(objects.exists(firstSnapshot)).as("被取代的快照删除，每个会话至多一个").isFalse();
    }

    @Test
    void aSessionWithAQueuedTurnIsNotReclaimedEvenWhenIdle() {
        SandboxService service = service();
        EnvironmentSpec environment = sandbox(SandboxSize.SMALL);
        TestSession session = session(environment);
        prepare(service, turn(session, environment)).orElseThrow();
        TestNode.create("node-a", clock)
                .enqueuer()
                .enqueue(new NewTurn(
                        session.tenantId(),
                        session.projectId(),
                        session.sessionId(),
                        session.mainThreadId(),
                        TestSessions.messages("下一条"),
                        0));

        clock.advance(Duration.ofMinutes(11));
        assertThat(reaper(service).reapOnce()).containsEntry(ReclaimOutcome.IN_USE, 1);

        assertThat(driver.live).hasSize(1);
        assertThat(sessionRow(session).get("sandbox_pod_ip")).isNotNull();
    }

    @Test
    void aReclaimThatFindsATurnHoldingTheSessionLockSkipsTheSession() throws Exception {
        SandboxService service = service();
        EnvironmentSpec environment = sandbox(SandboxSize.SMALL);
        TestSession session = session(environment);
        SandboxHandle first = prepare(service, turn(session, environment)).orElseThrow();
        clock.advance(Duration.ofMinutes(11));

        // 新的一轮正在会话行锁内重新连接沙箱
        FakeSandboxDriver.Gate resuming = driver.holdResume();
        CompletableFuture<Optional<SandboxHandle>> starting =
                CompletableFuture.supplyAsync(() -> prepare(service, turn(session, environment)), executor);
        resuming.awaitEntered();

        Map<ReclaimOutcome, Integer> outcomes = reaper(service).reapOnce();
        resuming.open();

        assertThat(outcomes).containsEntry(ReclaimOutcome.BUSY, 1);
        assertThat(starting.get(30, TimeUnit.SECONDS)).contains(first);
        assertThat(driver.live).containsKey(first.state());
        assertThat(reaper(service).reapOnce()).as("刚被使用过，不再是空闲的候选").isEmpty();
    }

    @Test
    void aTurnStartingDuringAReclaimWaitsAndGetsARestoredSandboxNotTheDeletedOne() throws Exception {
        SandboxService service = service();
        EnvironmentSpec environment = sandbox(SandboxSize.SMALL);
        TestSession session = session(environment);
        SandboxHandle first = prepare(service, turn(session, environment)).orElseThrow();
        driver.box(first).outputs().put("kept.txt", "回收前写的".getBytes(StandardCharsets.UTF_8));
        clock.advance(Duration.ofMinutes(11));

        // 回收器在会话行锁内打快照
        FakeSandboxDriver.Gate snapshotting = driver.holdSnapshot();
        CompletableFuture<Map<ReclaimOutcome, Integer>> reclaiming =
                CompletableFuture.supplyAsync(() -> reaper(service).reapOnce(), executor);
        snapshotting.awaitEntered();
        CompletableFuture<Optional<SandboxHandle>> starting =
                CompletableFuture.supplyAsync(() -> prepare(service, turn(session, environment)), executor);
        Thread.sleep(300);
        assertThat(starting).as("新的一轮等待会话行锁").isNotDone();
        snapshotting.open();

        assertThat(reclaiming.get(30, TimeUnit.SECONDS)).containsEntry(ReclaimOutcome.RECLAIMED, 1);
        SandboxHandle used = starting.get(30, TimeUnit.SECONDS).orElseThrow();
        assertThat(used.sandboxId()).isNotEqualTo(first.sandboxId());
        assertThat(driver.live).containsKey(used.state()).doesNotContainKey(first.state());
        assertThat(new String(driver.box(used).outputs().get("kept.txt"), StandardCharsets.UTF_8))
                .isEqualTo("回收前写的");
        assertThat(sessionRow(session).get("sandbox_pod_ip")).isEqualTo(used.podIp());
    }

    @Test
    void aSandboxThatDisappearedIsReplacedFromTheLastSnapshot() {
        SandboxService service = service();
        IdleSandboxReaper reaper = reaper(service);
        EnvironmentSpec environment = sandbox(SandboxSize.SMALL);
        TestSession session = session(environment);
        SandboxHandle first = prepare(service, turn(session, environment)).orElseThrow();
        driver.box(first).outputs().put("a.txt", "v1".getBytes(StandardCharsets.UTF_8));
        clock.advance(Duration.ofMinutes(11));
        reaper.reapOnce();
        SandboxHandle restored = prepare(service, turn(session, environment)).orElseThrow();

        driver.live.clear();
        SandboxHandle replaced = prepare(service, turn(session, environment)).orElseThrow();

        assertThat(replaced.sandboxId()).isNotIn(first.sandboxId(), restored.sandboxId());
        assertThat(driver.box(replaced).outputs()).containsKey("a.txt");
    }

    @Test
    void outputsAreMirroredIntoObjectStorageAtTheEndOfEachTurn() {
        SandboxService service = service(DataSize.ofBytes(1024));
        EnvironmentSpec environment = sandbox(SandboxSize.SMALL);
        TestSession session = session(environment);
        TurnContext turn = turn(session, environment);
        SandboxHandle handle = prepare(service, turn).orElseThrow();
        Map<String, byte[]> outputs = driver.box(handle).outputs();
        outputs.put("report.docx", "docx 内容".getBytes(StandardCharsets.UTF_8));
        outputs.put("data/a.csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        String prefix =
                "rt/outputs/" + session.tenantId() + "/" + session.projectId() + "/" + session.sessionId() + "/";

        afterTurn(service, turn, handle);

        assertThat(objects.get(prefix + "report.docx")).hasValue("docx 内容".getBytes(StandardCharsets.UTF_8));
        assertThat(objects.get(prefix + "data/a.csv")).hasValue("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        assertThat(DbAssertions.list(
                        "SELECT path FROM rt.sandbox_outputs WHERE session_id = ? ORDER BY path",
                        String.class,
                        session.sessionId()))
                .containsExactly("data/a.csv", "report.docx");

        outputs.remove("report.docx");
        outputs.put("data/a.csv", "a,b\n3,4\n".getBytes(StandardCharsets.UTF_8));
        outputs.put("big.bin", new byte[2048]);
        afterTurn(service, turn, handle);

        assertThat(objects.exists(prefix + "report.docx")).as("沙箱里删除的产物，副本也删除").isFalse();
        assertThat(objects.get(prefix + "data/a.csv")).hasValue("a,b\n3,4\n".getBytes(StandardCharsets.UTF_8));
        assertThat(objects.exists(prefix + "big.bin")).as("超过上限的文件不同步").isFalse();
        assertThat(DbAssertions.list(
                        "SELECT sha256 FROM rt.sandbox_outputs WHERE session_id = ? AND path = 'data/a.csv'",
                        String.class,
                        session.sessionId()))
                .containsExactly(FakeSandboxDriver.sha256("a,b\n3,4\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sandboxVcpuSecondsAreRecordedAsUsageAtTurnEndsAndAtReclaim() {
        SandboxService service = service();
        EnvironmentSpec environment = sandbox(SandboxSize.MEDIUM);
        TestSession session = session(environment);
        TurnContext turn = turn(session, environment);
        SandboxHandle handle = prepare(service, turn).orElseThrow();

        clock.advance(Duration.ofSeconds(90));
        afterTurn(service, turn, handle);
        clock.advance(Duration.ofMinutes(11));
        reaper(service).reapOnce();

        List<Map<String, Object>> usage = DbAssertions.rows(
                "SELECT payload->>'vcpu_seconds' AS seconds, payload->>'vcpu' AS vcpu, payload->>'turn_id' AS turn_id, "
                        + "payload->>'size' AS size, payload->>'sandbox_id' AS sandbox_id, "
                        + "payload->>'agent_version_id' AS agent_version_id "
                        + "FROM rt.outbox WHERE topic = 'usage' AND payload->>'kind' = 'sandbox_vcpu_seconds' "
                        + "AND payload->>'session_id' = ? ORDER BY created_at, id",
                session.sessionId());
        assertThat(usage).hasSize(2);
        assertThat(new BigDecimal((String) usage.get(0).get("seconds"))).isEqualByComparingTo("180");
        assertThat(usage.get(0)).containsEntry("vcpu", "2").containsEntry("size", "medium");
        assertThat(usage.get(0).get("turn_id")).isEqualTo(turn.platform().turnId());
        assertThat(usage.get(0))
                .containsEntry("sandbox_id", handle.sandboxId())
                .containsEntry("agent_version_id", agentVersionId);
        assertThat(new BigDecimal((String) usage.get(1).get("seconds")))
                .as("回收时把空闲的 11 分钟也计上")
                .isEqualByComparingTo("1320");
        assertThat(usage.get(1).get("turn_id")).isNull();
        assertThat(sessionRow(session).get("sandbox_metered_at")).isNull();
    }

    @Test
    void deletingTheSessionSandboxRemovesTheSandboxSnapshotsAndOutputs() {
        SandboxService service = service();
        EnvironmentSpec environment = sandbox(SandboxSize.SMALL);
        TestSession session = session(environment);
        TurnContext turn = turn(session, environment);
        SandboxHandle first = prepare(service, turn).orElseThrow();
        driver.box(first).outputs().put("r.txt", "x".getBytes(StandardCharsets.UTF_8));
        afterTurn(service, turn, first);
        clock.advance(Duration.ofMinutes(11));
        reaper(service).reapOnce();
        SandboxHandle restored = prepare(service, turn(session, environment)).orElseThrow();
        String snapshot = (String) sessionRow(session).get("sandbox_snapshot_key");

        PlatformContexts.run(session.context(), () -> service.deleteSessionSandbox(session.sessionId()));

        assertThat(driver.live).doesNotContainKey(restored.state());
        assertThat(objects.exists(snapshot)).isFalse();
        assertThat(objects.exists("rt/outputs/" + session.tenantId() + "/" + session.projectId() + "/"
                        + session.sessionId() + "/r.txt"))
                .isFalse();
        assertThat(DbAssertions.list(
                        "SELECT path FROM rt.sandbox_outputs WHERE session_id = ?", String.class, session.sessionId()))
                .isEmpty();
        Map<String, Object> row = sessionRow(session);
        assertThat(row.get("state")).isNull();
        assertThat(row.get("sandbox_pod_ip")).isNull();
        assertThat(row.get("sandbox_snapshot_key")).isNull();
    }
}
