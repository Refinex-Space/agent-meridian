package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.LOOKUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.engine.ThreadRef;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent.RescheduleReason;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.InvocationStart;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.runtime.dispatch.app.ClaimResult;
import cn.refinex.meridian.runtime.dispatch.app.LeaseHandle;
import cn.refinex.meridian.runtime.dispatch.domain.ClaimedTurn;
import cn.refinex.meridian.runtime.dispatch.domain.RequeueReason;
import cn.refinex.meridian.runtime.session.app.TurnExecution;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.session.app.WorkerProperties;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.ShutdownState;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * T06-A04（M0 验证 P2 完整路径）：两个节点争抢同一线程，失去租约的旧执行在中断、出错、优雅停机三条路径上，AgentScope 触发的
 * 状态保存、事件写入、工具账本写入全部被拒绝，数据库保持不变。
 *
 * <p>旧执行在内核层驱动：它还没有察觉租约丢失（本节点的 {@code LeaseRegistry} 仍是 epoch 1），所以租约守卫放行、AgentScope 照常
 * 走到保存状态的路径，拒绝只能来自数据库。状态端口外包一层记录被拒绝的写入，证明这些路径上确实发生了保存尝试。Worker 层的放弃
 * 另见 {@link #staleWorkerAbandonsTheTurnWhenRenewalFindsTheLeaseGoneAndWritesNothing}。
 */
class WorkerLeaseFencingIT {

    private static final Duration LEASE_EXPIRY = Duration.ofSeconds(31);

    @AfterEach
    void resetGracefulShutdown() {
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    @Test
    void interruptPathOfAStaleExecutionWritesNothing() throws Exception {
        Stale stale = Stale.start(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "a")))
                        .build(),
                WorkerProperties.defaults());

        assertThat(stale.nodeA.engine().interrupt(stale.thread())).isTrue();
        List<EngineEvent> events = stale.finish();

        assertThat(events.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.INTERRUPTED));
        stale.assertEverythingRejected(events);
        assertThat(stale.nodeA
                        .finisher()
                        .finish(stale.turn, "agv_test", new SessionEvent.StatusIdle(StopReason.INTERRUPTED)))
                .isInstanceOf(TurnResult.Abandoned.class);
        stale.assertDatabaseUnchanged();
    }

    @Test
    void errorPathOfAStaleExecutionWritesNothing() throws Exception {
        // 关闭批次 checkpoint：保存来自 AgentScope 出错时的 saveStateAfterCallFailure
        Stale stale = Stale.start(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "a")))
                        .failBeforeFirstChunk(new IllegalStateException("供应商故障"))
                        .build(),
                WorkerFixture.withoutCheckpoint());

        List<EngineEvent> events = stale.finish();

        assertThat(events).contains(new SessionEvent.StatusIdle(StopReason.ERROR));
        assertThat(events).anyMatch(SessionEvent.Error.class::isInstance);
        stale.assertEverythingRejected(events);
        stale.assertDatabaseUnchanged();
    }

    @Test
    void shutdownPathOfAStaleExecutionWritesNothing() throws Exception {
        Stale stale = Stale.start(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "a")))
                        .build(),
                WorkerProperties.defaults());

        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(
                () -> stale.nodeA.engine().shutdown(Duration.ofSeconds(60)).block());
        WorkerFixture.await(() -> GracefulShutdownManager.getInstance().getState(), s -> s != ShutdownState.RUNNING);
        List<EngineEvent> events = stale.finish();
        shutdown.get(90, TimeUnit.SECONDS);

        assertThat(events.getLast()).isEqualTo(new SessionEvent.StatusRescheduled(RescheduleReason.NODE_SHUTDOWN));
        stale.assertEverythingRejected(events);
        assertThat(stale.nodeA.finisher().requeue(stale.turn, RequeueReason.NODE_SHUTDOWN))
                .isInstanceOf(TurnResult.Abandoned.class);
        stale.assertDatabaseUnchanged();
    }

    @Test
    void staleWorkerAbandonsTheTurnWhenRenewalFindsTheLeaseGoneAndWritesNothing() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "a")))
                .build());
        WorkerNode nodeA = fixture.node("node-a");
        WorkerNode nodeB = fixture.node("node-b");
        TestSession session = fixture.session();
        fixture.lookupCalls.close();
        try {
            fixture.send(nodeA, session, "查一下 a", Delivery.QUEUE);
            TurnExecution execution = nodeA.claim();
            CompletableFuture<TurnResult> running = execution.run().toFuture();
            fixture.lookupCalls.awaitEntered();
            // 等工具开始前的事件都写完，接下来的放弃只可能来自续租
            WorkerFixture.await(
                    () -> fixture.events(nodeA, session),
                    events -> events.stream().anyMatch(e -> e.type() == EventType.SPAN_MODEL_REQUEST_END));
            fixture.clock.advance(LEASE_EXPIRY);
            WorkerFixture.reap(nodeB);
            ((ClaimResult.Claimed) nodeB.node().claimer().claim()).lease();
            ThreadSnapshot before = ThreadSnapshot.take(session);

            nodeA.node().renewer().renewAll();
            TurnResult result = running.get(30, TimeUnit.SECONDS);
            fixture.lookupCalls.open();
            Thread.sleep(200);

            assertThat(result)
                    .isEqualTo(new TurnResult.Abandoned(execution.turn().turnId(), "租约丢失：REVOKED"));
            assertThat(nodeA.node().registry().heldThreadIds()).isEmpty();
            assertThat(nodeA.node()
                            .meters()
                            .get("meridian.lease.lost")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
            assertThat(ThreadSnapshot.take(session)).isEqualTo(before);
        } finally {
            fixture.lookupCalls.open();
        }
    }

    /**
     * 一次被接管的旧执行：{@code node-a} 认领（epoch 1）并在内核层开始执行，工具卡在中途；时钟推进 31 秒，{@code node-b} 回收并认领
     * （epoch 2）；记录数据库快照后放行工具，让旧执行走到要测的路径。
     */
    private static final class Stale {

        final WorkerFixture fixture;
        final WorkerNode nodeA;
        final TestSession session;
        final ClaimedTurn turn;
        final RecordingStates statesA;
        final CompletableFuture<List<EngineEvent>> events;
        final ThreadSnapshot before;

        private Stale(
                WorkerFixture fixture,
                WorkerNode nodeA,
                TestSession session,
                ClaimedTurn turn,
                RecordingStates statesA,
                CompletableFuture<List<EngineEvent>> events,
                ThreadSnapshot before) {
            this.fixture = fixture;
            this.nodeA = nodeA;
            this.session = session;
            this.turn = turn;
            this.statesA = statesA;
            this.events = events;
            this.before = before;
        }

        static Stale start(ScriptedModel model, WorkerProperties properties) throws Exception {
            WorkerFixture fixture = new WorkerFixture(model);
            RecordingStates[] recording = new RecordingStates[1];
            WorkerNode nodeA = fixture.node("node-a", properties, states -> {
                recording[0] = new RecordingStates(states);
                return recording[0];
            });
            WorkerNode nodeB = fixture.node("node-b", properties, s -> s);
            TestSession session = fixture.session();
            String eventId = fixture.send(nodeA, session, "查一下 a", Delivery.QUEUE);
            LeaseHandle lease = ((ClaimResult.Claimed) nodeA.node().claimer().claim()).lease();
            ClaimedTurn turn = lease.turn();
            fixture.lookupCalls.close();
            TurnInput input = new TurnInput.NewMessages(
                    List.of(new InboundMessage(eventId, List.of(new MessageContent.Text("查一下 a")))), null);
            CopyOnWriteArrayList<EngineEvent> collected = new CopyOnWriteArrayList<>();
            CompletableFuture<List<EngineEvent>> done = nodeA.engine()
                    .runTurn(context(fixture, session, turn), input)
                    .doOnNext(collected::add)
                    .then(Mono.fromSupplier(() -> List.<EngineEvent>copyOf(collected)))
                    .toFuture();
            fixture.lookupCalls.awaitEntered();

            fixture.clock.advance(LEASE_EXPIRY);
            WorkerFixture.reap(nodeB);
            LeaseHandle taken = ((ClaimResult.Claimed) nodeB.node().claimer().claim()).lease();
            assertThat(taken.epoch()).isEqualTo(2);
            assertThat(nodeA.node()
                            .registry()
                            .find(session.mainThreadId())
                            .orElseThrow()
                            .epoch())
                    .as("旧执行还没察觉，本节点登记的仍是 epoch 1")
                    .isEqualTo(1);
            return new Stale(fixture, nodeA, session, turn, recording[0], done, ThreadSnapshot.take(session));
        }

        ThreadRef thread() {
            return new ThreadRef(session.sessionId(), session.mainThreadId());
        }

        /** 放行工具，等待旧执行的事件流结束。 */
        List<EngineEvent> finish() throws Exception {
            fixture.lookupCalls.open();
            return events.get(30, TimeUnit.SECONDS);
        }

        void assertEverythingRejected(List<EngineEvent> events) {
            assertThat(statesA.rejected.get())
                    .as("AgentScope 在这条路径上尝试保存状态，被数据库拒绝")
                    .isPositive();
            List<EngineEvent> persisted =
                    events.stream().filter(e -> e.type().isPersisted()).toList();
            assertThat(persisted).isNotEmpty();
            for (EngineEvent event : persisted) {
                assertThat(nodeA.node()
                                .events()
                                .append(EventBatch.leased(
                                        session.sessionId(),
                                        session.mainThreadId(),
                                        turn.turnId(),
                                        turn.epoch(),
                                        event)))
                        .as("旧执行写事件：%s", event.type().value())
                        .isEqualTo(new AppendResult.LeaseLost(turn.turnId(), turn.epoch()));
            }
            assertThatThrownBy(() -> nodeA.ledger()
                            .recordStart(new InvocationStart(
                                    session.mainThreadId(), "call_late", LOOKUP, SideEffect.IDEMPOTENT, null, Map.of()))
                            .contextWrite(PlatformContexts.write(
                                    context(fixture, session, turn).platform()))
                            .block(Duration.ofSeconds(10)))
                    .as("旧执行写工具账本")
                    .isInstanceOf(LeaseLostException.class);
        }

        void assertDatabaseUnchanged() {
            assertThat(ThreadSnapshot.take(session)).isEqualTo(before);
            assertThat(DbAssertions.list(
                            "SELECT status FROM rt.tool_invocations WHERE turn_id = ?", String.class, turn.turnId()))
                    .as("工具的结束记录同样被拒绝，账本中仍是开始时的 running")
                    .containsExactly("running");
        }
    }

    private static TurnContext context(WorkerFixture fixture, TestSession session, ClaimedTurn turn) {
        PlatformContext platform = PlatformContext.builder()
                .tenantId(turn.tenantId())
                .projectId(turn.projectId())
                .sessionId(turn.sessionId())
                .threadId(turn.threadId())
                .turnId(turn.turnId())
                .leaseEpoch(turn.epoch())
                .interaction(Interaction.ATTENDED)
                .build();
        return new TurnContext(
                platform,
                "u_1024",
                fixture.spec.agentVersionId(),
                fixture.environment.environmentId(),
                fixture.environment.revision(),
                fixture.spec.permissions(),
                null,
                null,
                null);
    }

    /** 记录被租约条件拒绝的写入次数的状态端口。 */
    private static final class RecordingStates implements AgentStateRepository {

        final AgentStateRepository delegate;
        final AtomicInteger rejected = new AtomicInteger();

        RecordingStates(AgentStateRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<StoredState> find(StateKey key) {
            return delegate.find(key);
        }

        @Override
        public long save(StateKey key, String json, StateFence fence) {
            return recording(() -> delegate.save(key, json, fence));
        }

        @Override
        public OptionalLong saveIfVersion(StateKey key, String json, long expectedVersion, StateFence fence) {
            return recording(() -> delegate.saveIfVersion(key, json, expectedVersion, fence));
        }

        @Override
        public boolean exists(String userId, String threadId) {
            return delegate.exists(userId, threadId);
        }

        @Override
        public void delete(StateKey key, StateFence fence) {
            recording(() -> {
                delegate.delete(key, fence);
                return null;
            });
        }

        @Override
        public void deleteAll(String userId, String threadId, StateFence fence) {
            recording(() -> {
                delegate.deleteAll(userId, threadId, fence);
                return null;
            });
        }

        @Override
        public Set<String> listThreadIds(String userId) {
            return delegate.listThreadIds(userId);
        }

        @Override
        public OptionalLong contentEpoch(String userId, String threadId) {
            return delegate.contentEpoch(userId, threadId);
        }

        private <T> T recording(Supplier<T> write) {
            try {
                return write.get();
            } catch (LeaseLostException e) {
                rejected.incrementAndGet();
                throw e;
            }
        }
    }
}
