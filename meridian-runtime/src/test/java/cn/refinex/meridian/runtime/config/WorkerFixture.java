package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.runtime.delivery.domain.InboxEntry;
import cn.refinex.meridian.runtime.dispatch.app.EnqueueResult;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
import cn.refinex.meridian.runtime.session.app.WorkerProperties;
import cn.refinex.meridian.runtime.stream.app.EventStreamPublisher;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.springframework.data.redis.connection.stream.MapRecord;

/**
 * Worker 集成测试的场景夹具：共享的时钟、模型脚本、智能体配置与记账工具，创建节点与会话，模拟 API 发送消息，读取事件。
 * 每个测试新建一个，开始前清空 {@code rt}。
 */
final class WorkerFixture {

    /** 记账工具：幂等的查询工具与有副作用的写入工具。 */
    static final String LOOKUP = "lookup";

    static final String WRITE = "write_record";

    final MutableClock clock = MutableClock.at(Instant.parse("2100-01-01T00:00:00Z"));
    final ScriptedModel model;
    final TestAgents agents = new TestAgents();
    final ResolvedAgentSpec spec = TestSpecs.agent().build();
    final EnvironmentSpec environment = TestSpecs.noneEnvironment();
    final LedgerTool.Calls lookupCalls = new LedgerTool.Calls();
    final LedgerTool.Calls writeCalls = new LedgerTool.Calls();

    WorkerFixture(ScriptedModel model) {
        RuntimeTestDatabase.truncate();
        this.model = model;
        agents.register(spec).register(environment);
    }

    WorkerNode node(String nodeId) {
        return node(nodeId, WorkerProperties.defaults(), UnaryOperator.identity());
    }

    WorkerNode node(String nodeId, WorkerProperties properties, UnaryOperator<AgentStateRepository> states) {
        Map<String, WorkerNode.ToolSpec> tools = new LinkedHashMap<>();
        tools.put(LOOKUP, new WorkerNode.ToolSpec(SideEffect.IDEMPOTENT, lookupCalls));
        tools.put(WRITE, new WorkerNode.ToolSpec(SideEffect.NON_IDEMPOTENT, writeCalls));
        return WorkerNode.create(new WorkerNode.Spec(nodeId, clock, model, agents, tools, properties, states));
    }

    static WorkerProperties withoutCheckpoint() {
        WorkerProperties defaults = WorkerProperties.defaults();
        return new WorkerProperties(
                defaults.maxConcurrentTurns(), defaults.shutdownTimeout(), defaults.pendingActionTimeout(), false);
    }

    TestSession session() {
        return TestSessions.create(clock, spec.agentVersionId(), environment.environmentId(), environment.revision());
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

    static <T> T await(Supplier<T> supplier, java.util.function.Predicate<T> done) {
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
