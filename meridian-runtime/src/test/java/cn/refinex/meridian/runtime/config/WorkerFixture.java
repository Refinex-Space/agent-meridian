package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.approval.app.PendingActionTimeouts;
import cn.refinex.meridian.runtime.approval.domain.PendingActionStatus;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import cn.refinex.meridian.runtime.approval.infra.PendingActionRepository;
import cn.refinex.meridian.runtime.approval.infra.system.PendingActionTimeoutRepository;
import cn.refinex.meridian.runtime.delivery.domain.InboxEntry;
import cn.refinex.meridian.runtime.dispatch.app.EnqueueResult;
import cn.refinex.meridian.runtime.dispatch.app.NodeChannel;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
import cn.refinex.meridian.runtime.egress.app.EgressGrants;
import cn.refinex.meridian.runtime.egress.app.EgressRevocations;
import cn.refinex.meridian.runtime.egress.infra.system.SandboxAddressRepository;
import cn.refinex.meridian.runtime.session.app.SessionService;
import cn.refinex.meridian.runtime.session.app.WorkerProperties;
import cn.refinex.meridian.runtime.session.infra.SessionRepository;
import cn.refinex.meridian.runtime.session.infra.ThreadRepository;
import cn.refinex.meridian.runtime.session.infra.system.ThreadLockRepository;
import cn.refinex.meridian.runtime.stream.app.EventStreamPublisher;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.AgentTool;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.springframework.data.redis.connection.stream.MapRecord;

/**
 * Worker 集成测试的场景夹具：共享的时钟、模型脚本、智能体配置与记账工具，创建节点与会话，模拟 API 发送消息，读取事件。
 * 每个测试新建一个，开始前清空 {@code rt}。
 */
final class WorkerFixture {

    /** 记账工具：幂等的查询工具与有副作用的写入工具。 */
    static final String LOOKUP = "lookup";

    static final String WRITE = "write_record";

    /**
     * 夹具智能体的权限：放行两个记账工具。每轮合成的权限总带着平台安全规则，AgentScope 不再走"平凡权限上下文一律放行"的
     * 旧路径（T08），DEFAULT 模式下没有命中 allow 的调用都需要确认，所以智能体要显式放行它的工具。
     */
    static final PermissionPolicy LEDGER_TOOLS_ALLOWED = new PermissionPolicy(
            PermissionMode.DEFAULT,
            List.of(
                    new PermissionRule(LOOKUP, null, PermissionBehavior.ALLOW, PolicyLayer.AGENT_VERSION),
                    new PermissionRule(WRITE, null, PermissionBehavior.ALLOW, PolicyLayer.AGENT_VERSION)));

    /** 平台工作区模式下的本机工作区根目录：P4 断言会话之后它仍然不存在（平台不在本机写入）。 */
    static final Path LOCAL_WORKSPACE_ROOT =
            Path.of(System.getProperty("java.io.tmpdir"), "meridian-it-agent-workspace");

    final MutableClock clock = MutableClock.at(Instant.parse("2100-01-01T00:00:00Z"));
    final ScriptedModel model;
    final Model agentModel;
    final TestAgents agents = new TestAgents();
    final ResolvedAgentSpec spec;
    EnvironmentSpec environment = TestSpecs.noneEnvironment();
    final LedgerTool.Calls lookupCalls = new LedgerTool.Calls();
    final LedgerTool.Calls writeCalls = new LedgerTool.Calls();

    /** 创建节点前加入的其他测试工具（每个节点共用同一个实例）。 */
    final List<AgentTool> extraTools = new ArrayList<>();

    /** 创建节点前登记：模型 ID → 模型，子智能体用自己的模型时使用（T13），其余模型 ID 用 {@link #agentModel}。 */
    final Map<String, Model> models = new LinkedHashMap<>();

    /** 创建节点前设置：用生产的平台工具、工作区与技能仓库装配（T10a）。 */
    boolean platform;

    /** {@link #useSandbox} 设置的 {@code sandbox} 档装配（T12a）。 */
    private WorkerNode.SandboxWiring sandbox;

    WorkerFixture(ScriptedModel model) {
        this(model, UnaryOperator.identity());
    }

    /**
     * 创建夹具，按需调整智能体配置（默认放行两个记账工具）。
     *
     * @param model 模型脚本
     * @param agent 调整智能体配置
     */
    WorkerFixture(ScriptedModel model, UnaryOperator<TestSpecs.AgentSpecBuilder> agent) {
        this(model, agent, model);
    }

    /**
     * 创建夹具，智能体使用另一个模型（例如以 {@code model} 为底层供应商的 {@code RoutedModel}，T09a）。
     *
     * @param model 模型脚本，测试读取它收到的请求
     * @param agent 调整智能体配置
     * @param agentModel 装配给智能体的模型
     */
    WorkerFixture(ScriptedModel model, UnaryOperator<TestSpecs.AgentSpecBuilder> agent, Model agentModel) {
        RuntimeTestDatabase.truncate();
        this.model = model;
        this.agentModel = agentModel;
        this.spec =
                agent.apply(TestSpecs.agent().permissions(LEDGER_TOOLS_ALLOWED)).build();
        agents.register(spec).register(environment);
    }

    /**
     * 改用 {@code sandbox} 档（small 规格）与平台模式，在创建节点与会话之前调用（T12a）。
     *
     * @param wiring 沙箱驱动与配置
     */
    void useSandbox(WorkerNode.SandboxWiring wiring) {
        this.sandbox = wiring;
        this.platform = true;
        this.environment = TestSpecs.sandboxEnvironment();
        agents.register(environment);
    }

    WorkerNode node(String nodeId) {
        return node(nodeId, WorkerProperties.defaults(), UnaryOperator.identity());
    }

    WorkerNode node(String nodeId, WorkerProperties properties, UnaryOperator<AgentStateRepository> states) {
        return node(nodeId, properties, states, RuntimeTestDatabase.dataSource());
    }

    WorkerNode node(
            String nodeId,
            WorkerProperties properties,
            UnaryOperator<AgentStateRepository> states,
            DataSource dataSource) {
        Map<String, WorkerNode.ToolSpec> tools = new LinkedHashMap<>();
        tools.put(LOOKUP, new WorkerNode.ToolSpec(SideEffect.IDEMPOTENT, lookupCalls));
        tools.put(WRITE, new WorkerNode.ToolSpec(SideEffect.NON_IDEMPOTENT, writeCalls));
        return WorkerNode.create(new WorkerNode.Spec(
                nodeId,
                clock,
                agentModel,
                AgentProfile.main(spec, environment),
                agents,
                tools,
                properties,
                states,
                dataSource,
                List.copyOf(extraTools),
                platform,
                sandbox,
                Map.copyOf(models)));
    }

    static WorkerProperties withoutCheckpoint() {
        WorkerProperties defaults = WorkerProperties.defaults();
        return new WorkerProperties(
                defaults.maxConcurrentTurns(),
                defaults.shutdownTimeout(),
                defaults.pendingActionTimeout(),
                false,
                defaults.maxSubagentsPerTurn());
    }

    TestSession session() {
        return TestSessions.create(clock, spec.agentVersionId(), environment.environmentId(), environment.revision());
    }

    /** 只放行查询工具：写入工具在 DEFAULT 模式下默认需要确认，用来制造"等待回应"。 */
    static TestSpecs.AgentSpecBuilder lookupOnlyAllowed(TestSpecs.AgentSpecBuilder agent) {
        return agent.permissions(new PermissionPolicy(
                PermissionMode.DEFAULT,
                List.of(new PermissionRule(LOOKUP, null, PermissionBehavior.ALLOW, PolicyLayer.AGENT_VERSION))));
    }

    /** 会话用例（创建、删除、归档），与生产相同的依赖，内核未装配时的保留工具名。 */
    SessionService sessions(WorkerNode node) {
        DataSource dataSource = RuntimeTestDatabase.dataSource();
        return new SessionService(
                new SessionRepository(dataSource),
                new ThreadRepository(dataSource),
                new ThreadLockRepository(dataSource),
                new PendingActionRepository(dataSource),
                node.node().transitions(),
                new NodeChannel(RuntimeTestRedis.reactiveMessaging()),
                node.node().events(),
                agents,
                (agent, environment) -> PlatformToolNames.ALL,
                new OutboxWriter(dataSource, "rt", clock),
                new EgressRevocations(
                        new EgressGrants(
                                new SandboxAddressRepository(dataSource),
                                new ProvidedAgentConfigPort(agents),
                                node.node().meters()),
                        RuntimeTestRedis.reactiveMessaging()),
                RuntimeTestDatabase.transactions(),
                clock);
    }

    /** 待处理动作的超时扫描（与生产相同的依赖）。 */
    PendingActionTimeouts timeouts(WorkerNode node) {
        DataSource dataSource = RuntimeTestDatabase.dataSource();
        return new PendingActionTimeouts(
                new PendingActionTimeoutRepository(dataSource),
                new PendingActionRepository(dataSource),
                new ThreadLockRepository(dataSource),
                node.resolver(),
                RuntimeTestDatabase.transactions(),
                clock);
    }

    /** 同一项目中的另一个会话。 */
    TestSession anotherSession(TestSession sameProject) {
        return TestSessions.another(
                sameProject, clock, spec.agentVersionId(), environment.environmentId(), environment.revision());
    }

    /** 指定值守方式与自定义工具的会话。 */
    TestSession session(Interaction interaction, List<CustomToolDeclaration> customTools) {
        return TestSessions.create(
                clock,
                spec.agentVersionId(),
                environment.environmentId(),
                environment.revision(),
                interaction,
                customTools);
    }

    /**
     * 经发送事件的用例（与 API 相同的投递、中断与回应语义）发送一批用户事件，调用方是会话所在项目的 API Key。
     *
     * @return 持久化后的事件
     */
    List<StoredEvent> post(WorkerNode via, TestSession session, UserEvent... events) {
        PlatformContext context = PlatformContext.builder()
                .tenantId(session.tenantId())
                .projectId(session.projectId())
                .apiKeyId("key_test")
                .build();
        return PlatformContexts.call(context, () -> via.intake().send(session.sessionId(), List.of(events)));
    }

    /** 一条文本用户消息。 */
    static UserEvent.Message message(String text, Delivery delivery) {
        return new UserEvent.Message(List.of(new MessageContent.Text(text)), delivery);
    }

    /** 读取会话中的待处理动作（全部状态），按创建顺序。 */
    List<StoredPendingAction> actions(TestSession session) {
        return PlatformContexts.call(session.context(), () -> {
            PendingActionRepository repository = new PendingActionRepository(RuntimeTestDatabase.dataSource());
            List<StoredPendingAction> all = new ArrayList<>();
            for (PendingActionStatus status : PendingActionStatus.values()) {
                all.addAll(repository.page(session.sessionId(), status, PageRequest.first(100)));
            }
            all.sort(java.util.Comparator.comparing(StoredPendingAction::id));
            return all;
        });
    }

    /** 线程上最新一轮的 ID 与状态（按创建时间）。 */
    static Map<String, Object> latestTurn(TestSession session) {
        return DbAssertions.row(
                "SELECT id, kind, status, stop_reason, interrupt_requested_at FROM rt.turns WHERE thread_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT 1",
                session.mainThreadId());
    }

    /**
     * 模拟 API 发送一条 {@code user.message}（T07 的做法）：写事件；线程空闲时直接入队一轮，否则留在收件箱。
     *
     * @return 事件 ID
     */
    String send(WorkerNode via, TestSession session, String text, Delivery delivery) {
        String eventId = appendUserMessage(via, session, text, delivery);
        PlatformContexts.run(session.context(), () -> {
            InboundMessage message = new InboundMessage(eventId, List.of(new MessageContent.Text(text)));
            EnqueueResult enqueued = via.node()
                    .enqueuer()
                    .enqueue(NewTurn.of(
                            session.tenantId(),
                            session.projectId(),
                            session.sessionId(),
                            session.mainThreadId(),
                            new TurnInput.NewMessages(List.of(message), null)));
            if (enqueued instanceof EnqueueResult.ThreadBusy) {
                via.inbox().add(new InboxEntry(session.sessionId(), session.mainThreadId(), eventId, delivery));
            }
        });
        return eventId;
    }

    /** 只写 {@code user.message} 事件，返回事件 ID；收件箱与入队由调用方决定。 */
    String appendUserMessage(WorkerNode via, TestSession session, String text, Delivery delivery) {
        AppendResult result = via.node()
                .events()
                .append(EventBatch.unfenced(
                        session.sessionId(),
                        session.mainThreadId(),
                        List.of(new UserEvent.Message(List.of(new MessageContent.Text(text)), delivery))));
        return ((AppendResult.Appended) result).events().getFirst().id();
    }

    List<StoredEvent> events(WorkerNode via, TestSession session) {
        return via.node().events().listAfter(session.sessionId(), 0, 1000);
    }

    static List<String> types(List<StoredEvent> events) {
        return events.stream().map(e -> e.type().value()).toList();
    }

    static List<StoredEvent> ofType(List<StoredEvent> events, EventType type) {
        return events.stream().filter(e -> e.type() == type).toList();
    }

    /** 等待会话 Stream 中出现至少 {@code count} 个带 seq 的条目（XADD 在提交后异步执行），返回全部条目。 */
    static List<MapRecord<String, Object, Object>> awaitStream(String sessionId, int persistedCount) {
        return await(
                () -> RuntimeTestRedis.readAll(EventStreamPublisher.streamKey(sessionId)),
                records -> records.stream()
                                .filter(r -> r.getValue().containsKey("seq"))
                                .count()
                        >= persistedCount);
    }

    /**
     * 让节点的回收器回收过期的租约，直到回收到一个轮次。正在被写入的轮次行被 {@code FOR SHARE} 锁住时回收器会跳过它
     * （ADR-0027 第 3 条），生产中下个周期再处理，测试里立即重试。
     */
    static void reap(WorkerNode node) {
        await(() -> node.node().reaper().reapExpired(), reaped -> reaped == 1);
    }

    static <T> T await(Supplier<T> supplier, Predicate<T> done) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        T value = supplier.get();
        while (!done.test(value)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("等待超时，最后的值：" + value);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            value = supplier.get();
        }
        return value;
    }

    static String newEventId() {
        return Ids.newId(IdPrefix.EVENT);
    }
}
