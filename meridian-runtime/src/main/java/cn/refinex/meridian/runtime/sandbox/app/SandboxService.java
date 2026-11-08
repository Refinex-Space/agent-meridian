package cn.refinex.meridian.runtime.sandbox.app;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver.OutputFile;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver.SandboxRequest;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.port.AgentConfigPort;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.sandbox.domain.OutputSyncPlan;
import cn.refinex.meridian.runtime.sandbox.domain.SandboxMetering;
import cn.refinex.meridian.runtime.sandbox.domain.SandboxObjectKeys;
import cn.refinex.meridian.runtime.sandbox.domain.SandboxRecord;
import cn.refinex.meridian.runtime.sandbox.domain.SandboxUsageMessage;
import cn.refinex.meridian.runtime.sandbox.domain.SessionSandbox;
import cn.refinex.meridian.runtime.sandbox.infra.SandboxOutputRepository;
import cn.refinex.meridian.runtime.sandbox.infra.SessionSandboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * 会话沙箱的生命周期（架构设计「会话沙箱」）：何时创建、何时回收、状态存在哪里由本服务决定，具体操作经 {@link SandboxDriver}。
 *
 * <ul>
 *   <li><b>获取</b>（{@link #prepare}）：{@code sandbox} 档的轮次开始前，在会话行锁内确保会话有一个活着的沙箱：已有的重新连接，没有的
 *       从快照恢复或从预热池认领，状态与 Pod IP 落库，{@code sandbox_last_used_at} 记为当时。之后按智能体版本的技能同步
 *       {@code /workspace/.meridian/skills/}（摘要不一致才重写）。内核随后经 {@code SandboxStatePort} 读出它，作为外部沙箱注入。
 *   <li><b>轮次结束</b>（{@link #afterTurn}）：{@code outputs/} 按清单同步到对象存储 {@code rt/outputs/{tenant}/{project}/{session}/}；
 *       vCPU 秒计量到当时。
 *   <li><b>空闲回收</b>（{@link #reclaim}）：在会话行锁内（{@code SKIP LOCKED}）重新判定空闲、确认没有 {@code queued}、
 *       {@code running}、{@code awaiting_action} 的轮次，然后快照、删除沙箱、清空 Pod IP、记下快照键，同一事务提交；被取代的旧快照在
 *       提交后删除（每个会话至多保留一个快照）。轮次的获取也要这把行锁，所以回收与新轮次不会交错：回收先拿到锁时，新轮次等它提交后
 *       从快照恢复；新轮次先拿到锁时，回收跳过这个会话。
 *   <li><b>删除会话</b>（{@link #deleteSessionSandbox}）：删除沙箱、全部快照与产物副本（会话删除的总流程在 T17a）。
 * </ul>
 *
 * <p>行锁内会执行远程操作（认领约 0.1 秒，100 MB 工作区的快照或恢复约 3 至 5 秒，P5），单个操作的等待上限是
 * {@link SandboxProperties#operationTimeout()}；持锁期间同一会话的事件写入会等待，这只发生在会话的第一轮、回收后的第一轮与回收时。
 *
 * <p>方法会阻塞，要求调用线程上绑定了该会话所在项目的 {@code PlatformContext}（{@link #reclaim} 自己建立）。线程安全：无状态。
 */
@Service
public class SandboxService {

    /** 认领耗时的指标名，导出为 {@code meridian_sandbox_claim_seconds}（架构设计「可观测性」）；只计从预热池认领。 */
    public static final String CLAIM_METRIC = "meridian.sandbox.claim";

    /** 用量的 outbox 主题（架构设计「跨服务数据流」）。 */
    static final String USAGE_TOPIC = "usage";

    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);

    private final ObjectProvider<SandboxDriver> drivers;
    private final AgentConfigPort configs;
    private final SessionSandboxRepository sessions;
    private final SandboxOutputRepository outputs;
    private final ObjectStore objects;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final SandboxProperties properties;
    private final Timer claimTimer;
    private final Clock clock;

    /**
     * 创建服务。
     *
     * @param drivers 沙箱驱动；没有配置沙箱集群时不存在，{@code sandbox} 档的轮次以错误结束
     * @param configs 智能体版本、环境与技能配置
     * @param sessions 会话的沙箱列
     * @param outputs 产物清单
     * @param objects 对象存储
     * @param outbox outbox 写入，用量用
     * @param transactions 事务模板
     * @param properties 沙箱配置
     * @param meters 指标注册表
     * @param clock 时钟
     */
    public SandboxService(
            ObjectProvider<SandboxDriver> drivers,
            AgentConfigPort configs,
            SessionSandboxRepository sessions,
            SandboxOutputRepository outputs,
            ObjectStore objects,
            OutboxWriter outbox,
            TransactionTemplate transactions,
            SandboxProperties properties,
            MeterRegistry meters,
            Clock clock) {
        this.drivers = drivers;
        this.configs = configs;
        this.sessions = sessions;
        this.outputs = outputs;
        this.objects = objects;
        this.outbox = outbox;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
        this.claimTimer = Timer.builder(CLAIM_METRIC)
                .description("从预热池认领会话沙箱的耗时")
                .publishPercentileHistogram()
                .register(meters);
    }

    /**
     * 准备本轮的会话沙箱。{@code none} 档什么都不做。
     *
     * @param turn 本轮的上下文
     * @return 会话沙箱；{@code none} 档、或没有配置沙箱集群时为空
     * @throws cn.refinex.meridian.engine.spi.error.SandboxUnavailableException 沙箱基础设施暂时不可用（可重试）
     */
    public Optional<SandboxHandle> prepare(TurnContext turn) {
        EnvironmentSpec environment = await(configs.environment(turn.environmentId(), turn.environmentRevision()));
        if (environment.tier() != EnvironmentSpec.Tier.SANDBOX) {
            return Optional.empty();
        }
        SandboxDriver driver = drivers.getIfAvailable();
        if (driver == null) {
            // 配置问题：不准备沙箱，内核取不到会话沙箱时以错误结束本轮，错误事件里写明原因
            log.warn(
                    "没有配置会话沙箱（meridian.runtime.sandbox.enabled=false），sandbox 档的轮次将以错误结束 sessionId={}",
                    turn.platform().sessionId());
            return Optional.empty();
        }
        String sessionId = turn.platform().sessionId();
        AtomicReference<SandboxHandle> fresh = new AtomicReference<>();
        SandboxHandle handle;
        try {
            handle = transactions.execute(tx -> acquire(driver, turn, environment, fresh));
        } catch (RuntimeException e) {
            // 事务没有提交：新认领的沙箱没有记进会话，删掉它，否则没人能找到它
            SandboxHandle orphan = fresh.get();
            if (orphan != null) {
                try {
                    run(driver.delete(orphan));
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
        syncSkills(driver, turn, handle);
        log.debug("会话沙箱已就绪 sessionId={} sandbox={}", sessionId, handle.sandboxId());
        return Optional.of(handle);
    }

    /**
     * 轮次结束时的收尾：同步产物、计量 vCPU 秒。失败只记日志，不影响本轮的结束。
     *
     * @param turn 本轮的上下文
     * @param handle 本轮的会话沙箱
     * @param syncOutputs 是否同步产物（置回队列的轮次不同步，下一次执行结束时再同步）
     */
    public void afterTurn(TurnContext turn, SandboxHandle handle, boolean syncOutputs) {
        Objects.requireNonNull(handle, "handle");
        if (syncOutputs) {
            try {
                syncOutputs(turn.platform(), handle);
            } catch (RuntimeException e) {
                log.warn("产物同步失败，下一轮结束时重试 sessionId={}", turn.platform().sessionId(), e);
            }
        }
        try {
            transactions.executeWithoutResult(
                    tx -> sessions.lock(turn.platform().sessionId(), false)
                            .ifPresent(row -> meter(row, clock.instant(), turn.platform())));
        } catch (RuntimeException e) {
            log.warn("沙箱计量失败，下一次计量时补上 sessionId={}", turn.platform().sessionId(), e);
        }
    }

    /**
     * 空闲回收一个会话的沙箱：在会话行锁内重新判定，满足条件才快照、删除、清空 Pod IP。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param cutoff 空闲截止时间：最近一次使用早于它才回收
     * @return 判定结果
     */
    public ReclaimOutcome reclaim(String tenantId, String projectId, String sessionId, Instant cutoff) {
        PlatformContext context = PlatformContext.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .sessionId(sessionId)
                .build();
        return PlatformContexts.call(context, () -> {
            SandboxDriver driver = driver();
            AtomicReference<String> superseded = new AtomicReference<>();
            ReclaimOutcome outcome = transactions.execute(tx -> reclaimLocked(driver, context, cutoff, superseded));
            deleteSnapshot(superseded.get());
            if (outcome == ReclaimOutcome.RECLAIMED || outcome == ReclaimOutcome.LOST) {
                log.info("会话沙箱已回收 sessionId={} outcome={}", sessionId, outcome);
            }
            return outcome;
        });
    }

    /**
     * 删除会话的沙箱部分：沙箱、全部快照、产物副本与清单（会话删除的总流程在 T17a）。
     *
     * @param sessionId 会话 ID
     */
    public void deleteSessionSandbox(String sessionId) {
        PlatformContext context = PlatformContexts.current();
        transactions.executeWithoutResult(tx -> sessions.lock(sessionId, false).ifPresent(row -> {
            Instant now = clock.instant();
            row.handle().ifPresent(handle -> run(driver().delete(handle)));
            meter(row, now, context);
            sessions.clearSandbox(sessionId, null, now);
            outputs.deleteAll(sessionId);
        }));
        objects.deleteByPrefix(SandboxObjectKeys.outputsPrefix(context.tenantId(), context.projectId(), sessionId));
        objects.deleteByPrefix(SandboxObjectKeys.snapshotsPrefix(context.tenantId(), context.projectId(), sessionId));
        log.info("已删除会话的沙箱、快照与产物 sessionId={}", sessionId);
    }

    /** 在会话行锁内取得活着的沙箱。在事务中执行。 */
    private SandboxHandle acquire(
            SandboxDriver driver, TurnContext turn, EnvironmentSpec environment, AtomicReference<SandboxHandle> fresh) {
        PlatformContext platform = turn.platform();
        String sessionId = platform.sessionId();
        SessionSandbox row =
                sessions.lock(sessionId, false).orElseThrow(() -> new NotFoundException("会话不存在：" + sessionId));
        Instant now = clock.instant();
        Optional<SandboxHandle> current = row.handle();
        if (current.isPresent()) {
            Optional<SandboxHandle> resumed = resume(driver, current.get());
            if (resumed.isPresent()) {
                sessions.saveSandbox(
                        sessionId,
                        SandboxRecord.of(resumed.get(), row.sandbox().size()),
                        resumed.get().podIp(),
                        now,
                        null);
                return resumed.get();
            }
            // 沙箱在回收之外消失了（Pod 被驱逐、claim 被删）：结算它的用量，按没有沙箱处理
            log.warn(
                    "会话沙箱已不存在，重新获取 sessionId={} sandbox={}",
                    sessionId,
                    current.get().sandboxId());
            meter(row, now, platform);
        }
        SandboxRequest request = new SandboxRequest(sessionId, environment.size());
        SandboxHandle created = restoreOrCreate(driver, request, row.snapshotKey());
        fresh.set(created);
        sessions.saveSandbox(sessionId, SandboxRecord.of(created, environment.size()), created.podIp(), now, now);
        return created;
    }

    private Optional<SandboxHandle> resume(SandboxDriver driver, SandboxHandle handle) {
        try {
            return Optional.of(await(driver.resume(handle.state())));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    private SandboxHandle restoreOrCreate(SandboxDriver driver, SandboxRequest request, String snapshotKey) {
        if (snapshotKey != null) {
            try {
                return await(driver.restore(request, snapshotKey));
            } catch (NotFoundException e) {
                log.warn("快照不存在，新建空的沙箱 sessionId={} snapshot={}", request.sessionId(), snapshotKey);
            }
        }
        Timer.Sample sample = Timer.start();
        SandboxHandle created = await(driver.create(request));
        sample.stop(claimTimer);
        return created;
    }

    private void syncSkills(SandboxDriver driver, TurnContext turn, SandboxHandle handle) {
        ResolvedAgentSpec agent = await(configs.agentVersion(turn.agentVersionId()));
        List<SkillDefinition> skills =
                agent.skills().stream().map(ref -> await(configs.skill(ref))).toList();
        if (Boolean.TRUE.equals(await(driver.syncSkills(handle, skills)))) {
            log.info("技能资源已写入会话沙箱 sessionId={} skills={}", turn.platform().sessionId(), skills.size());
        }
    }

    private void syncOutputs(PlatformContext platform, SandboxHandle handle) {
        SandboxDriver driver = driver();
        String sessionId = platform.sessionId();
        List<OutputFile> listing = await(driver.listOutputs(handle));
        Map<String, String> synced = outputs.synced(sessionId);
        OutputSyncPlan plan = OutputSyncPlan.of(
                listing, synced, properties.outputMaxFileSize().toBytes());
        for (OutputFile file : plan.skipped()) {
            log.warn("产物超过同步上限，未同步 sessionId={} path={} size={}", sessionId, file.path(), file.size());
        }
        for (OutputFile file : plan.uploads()) {
            byte[] content = await(driver.readOutput(handle, file.path()));
            String key = SandboxObjectKeys.output(platform.tenantId(), platform.projectId(), sessionId, file.path());
            objects.put(key, content, contentType(file.path()));
            // 以读到的内容为准：清单与读取之间文件可能又被改过
            outputs.upsert(sessionId, file.path(), content.length, sha256(content), key, clock.instant());
        }
        for (String path : plan.deletions()) {
            objects.delete(SandboxObjectKeys.output(platform.tenantId(), platform.projectId(), sessionId, path));
            outputs.delete(sessionId, path);
        }
        if (!plan.isEmpty()) {
            log.info(
                    "产物已同步 sessionId={} uploaded={} deleted={}",
                    sessionId,
                    plan.uploads().size(),
                    plan.deletions().size());
        }
    }

    /** 回收的判定与操作，在事务中执行。 */
    private ReclaimOutcome reclaimLocked(
            SandboxDriver driver, PlatformContext context, Instant cutoff, AtomicReference<String> superseded) {
        String sessionId = context.sessionId();
        Optional<SessionSandbox> locked = sessions.lock(sessionId, true);
        if (locked.isEmpty()) {
            return ReclaimOutcome.BUSY;
        }
        SessionSandbox row = locked.get();
        Optional<SandboxHandle> handle = row.handle();
        if (handle.isEmpty()) {
            return ReclaimOutcome.NO_SANDBOX;
        }
        if (row.lastUsedAt() == null || !row.lastUsedAt().isBefore(cutoff)) {
            return ReclaimOutcome.IN_USE;
        }
        if (sessions.hasActiveTurns(sessionId)) {
            return ReclaimOutcome.IN_USE;
        }
        String snapshotKey;
        try {
            snapshotKey = await(driver.snapshot(handle.get()));
        } catch (NotFoundException e) {
            // 沙箱已不存在：没有可快照的内容，保留原来的快照
            Instant now = clock.instant();
            meter(row, now, context);
            sessions.clearSandbox(sessionId, row.snapshotKey(), now);
            return ReclaimOutcome.LOST;
        }
        run(driver.delete(handle.get()));
        Instant now = clock.instant();
        meter(row, now, context);
        sessions.clearSandbox(sessionId, snapshotKey, now);
        if (row.snapshotKey() != null && !row.snapshotKey().equals(snapshotKey)) {
            superseded.set(row.snapshotKey());
        }
        return ReclaimOutcome.RECLAIMED;
    }

    /** 计量从 {@code sandbox_metered_at} 到 {@code now} 的 vCPU 秒并推进起点。在持有会话行锁的事务中执行。 */
    private void meter(SessionSandbox row, Instant now, PlatformContext platform) {
        if (row.sandbox() == null || row.meteredAt() == null) {
            return;
        }
        SandboxMetering.vcpuSeconds(row.sandbox().size(), row.meteredAt(), now).ifPresent(seconds -> {
            outbox.append(
                    USAGE_TOPIC,
                    new SandboxUsageMessage(
                            SandboxUsageMessage.KIND,
                            now,
                            platform.tenantId(),
                            platform.projectId(),
                            row.agentVersionId(),
                            row.sessionId(),
                            platform.threadId(),
                            platform.turnId(),
                            row.sandbox().sandboxId(),
                            row.sandbox().size().value(),
                            SandboxMetering.vcpu(row.sandbox().size()),
                            row.meteredAt(),
                            now,
                            seconds));
            sessions.meteredUntil(row.sessionId(), now);
        });
    }

    private void deleteSnapshot(String key) {
        if (key == null) {
            return;
        }
        try {
            objects.delete(key);
        } catch (RuntimeException e) {
            log.warn("删除被取代的快照失败，删除会话时按前缀清除 key={}", key, e);
        }
    }

    private SandboxDriver driver() {
        SandboxDriver driver = drivers.getIfAvailable();
        if (driver == null) {
            throw new IllegalStateException("没有配置会话沙箱（meridian.runtime.sandbox.enabled=false），不能执行 sandbox 档的轮次");
        }
        return driver;
    }

    /** 在当前线程上等待一个有结果的端口操作，把本线程的 {@code PlatformContext} 带进 Reactor Context。 */
    private <T> T await(Mono<T> operation) {
        T value = withContext(operation).block(properties.operationTimeout());
        if (value == null) {
            throw new IllegalStateException("沙箱操作没有返回结果");
        }
        return value;
    }

    /** 在当前线程上等待一个没有结果的端口操作。 */
    private void run(Mono<Void> operation) {
        withContext(operation).block(properties.operationTimeout());
    }

    private static <T> Mono<T> withContext(Mono<T> operation) {
        return PlatformContexts.find()
                .map(context -> operation.contextWrite(PlatformContexts.write(context)))
                .orElse(operation);
    }

    private static String contentType(String path) {
        return MediaTypeFactory.getMediaType(path)
                .orElse(MediaType.APPLICATION_OCTET_STREAM)
                .toString();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    /** 空闲回收的判定结果。 */
    public enum ReclaimOutcome {
        /** 已快照并删除。 */
        RECLAIMED,
        /** 沙箱在回收前已不存在，清空了状态、保留原快照。 */
        LOST,
        /** 会话行被其他事务锁住（正在获取沙箱、写事件或被另一节点回收），本次跳过。 */
        BUSY,
        /** 刚被使用过或有活动轮次，不回收。 */
        IN_USE,
        /** 会话已经没有沙箱。 */
        NO_SANDBOX
    }
}
