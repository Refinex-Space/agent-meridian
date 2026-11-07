package cn.refinex.meridian.runtime.stream.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.StreamEvent;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.runtime.dispatch.app.ClaimResult;
import cn.refinex.meridian.runtime.dispatch.app.LeaseHandle;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.TestNode;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * T05-A07：同一会话内事件 seq 严格递增且连续，Worker（带租约）与 API（不带租约）并发写入时不重复、不跳号；
 * 租约丢失的写入不占用 seq。另验证状态事件推进会话与线程状态，以及 {@code EventSinkPort} 在 Reactor 线程上经
 * {@code Blocking} 写入、BlockHound 无违规（T05-A08）。
 */
class SessionEventWriterIT {

    private static final int WRITERS_PER_KIND = 4;
    private static final int WRITES_PER_WRITER = 40;

    private final MutableClock clock = MutableClock.at(Instant.parse("2100-01-01T00:00:00Z"));
    private TestNode node;
    private TestSession session;
    private LeaseHandle lease;

    @BeforeEach
    void setUp() {
        RuntimeTestDatabase.truncate();
        node = TestNode.create("node-a", clock);
        session = TestSessions.create(clock);
        node.enqueuer().enqueue(session.turn("事件写入"));
        lease = ((ClaimResult.Claimed) node.claimer().claim()).lease();
    }

    @Test
    void concurrentWorkerAndApiWritesGetStrictlyIncreasingContiguousSeqs() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(WRITERS_PER_KIND * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        try {
            for (int w = 0; w < WRITERS_PER_KIND; w++) {
                int writer = w;
                futures.add(executor.submit(() -> {
                    start.await();
                    List<Long> seqs = new ArrayList<>();
                    for (int i = 0; i < WRITES_PER_WRITER; i++) {
                        AppendResult result = node.events()
                                .append(EventBatch.leased(
                                        session.sessionId(),
                                        session.mainThreadId(),
                                        lease.turn().turnId(),
                                        lease.epoch(),
                                        new AgentOutputEvent.Thinking("w" + writer + ":" + i, "思考")));
                        seqs.add(((AppendResult.Appended) result).lastSeq());
                    }
                    return seqs;
                }));
                futures.add(executor.submit(() -> {
                    start.await();
                    List<Long> seqs = new ArrayList<>();
                    for (int i = 0; i < WRITES_PER_WRITER; i++) {
                        // API 写入：每次两条 user.message 一批
                        AppendResult result = node.events()
                                .append(EventBatch.unfenced(
                                        session.sessionId(),
                                        session.mainThreadId(),
                                        List.of(userMessage(), userMessage())));
                        List<StoredEvent> written = ((AppendResult.Appended) result).events();
                        assertThat(written.get(1).seq())
                                .isEqualTo(written.get(0).seq() + 1);
                        seqs.add(written.get(1).seq());
                    }
                    return seqs;
                }));
            }
            start.countDown();
            for (Future<List<Long>> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS))
                        .as("每个写入方看到的 seq 递增")
                        .isSorted();
            }
        } finally {
            executor.shutdownNow();
        }

        long expectedTotal = (long) WRITERS_PER_KIND * WRITES_PER_WRITER * 3;
        List<StoredEvent> events = node.events().listAfter(session.sessionId(), 0, 10_000);
        assertThat(events)
                .extracting(StoredEvent::seq)
                .containsExactlyElementsOf(
                        LongStream.rangeClosed(1, expectedTotal).boxed().toList());
        assertThat(events).extracting(StoredEvent::id).doesNotHaveDuplicates();
        assertThat(DbAssertions.list("SELECT last_seq FROM rt.sessions WHERE id = ?", Long.class, session.sessionId()))
                .containsExactly(expectedTotal);
    }

    @Test
    void leaseLostWriteWritesNothingAndConsumesNoSeq() {
        node.events().append(EventBatch.unfenced(session.sessionId(), session.mainThreadId(), List.of(userMessage())));

        AppendResult lost = node.events()
                .append(EventBatch.leased(
                        session.sessionId(),
                        session.mainThreadId(),
                        lease.turn().turnId(),
                        lease.epoch() + 1,
                        new AgentOutputEvent.Thinking("b1", "旧执行")));
        AppendResult otherNode = TestNode.create("node-b", clock)
                .events()
                .append(EventBatch.leased(
                        session.sessionId(),
                        session.mainThreadId(),
                        lease.turn().turnId(),
                        lease.epoch(),
                        new AgentOutputEvent.Thinking("b2", "别的节点")));
        AppendResult next = node.events()
                .append(EventBatch.unfenced(session.sessionId(), session.mainThreadId(), List.of(userMessage())));

        assertThat(lost).isEqualTo(new AppendResult.LeaseLost(lease.turn().turnId(), lease.epoch() + 1));
        assertThat(otherNode).isInstanceOf(AppendResult.LeaseLost.class);
        assertThat(((AppendResult.Appended) next).lastSeq()).as("没有跳号").isEqualTo(2);
        assertThat(node.events().listAfter(session.sessionId(), 0, 100)).hasSize(2);
    }

    @Test
    void nulCharactersInModelOutputDoNotBreakTheWrite() {
        // 登记事项 D11：jsonb 拒绝 \u0000，写入前替换为 U+FFFD
        AppendResult result = node.events()
                .append(EventBatch.leased(
                        session.sessionId(),
                        session.mainThreadId(),
                        lease.turn().turnId(),
                        lease.epoch(),
                        new AgentOutputEvent.Message("b1", List.of(new MessageContent.Text("前\u0000后")))));

        assertThat(result).isInstanceOf(AppendResult.Appended.class);
        AgentOutputEvent.Message stored = (AgentOutputEvent.Message) EngineJson.readEvent(
                EventType.AGENT_MESSAGE,
                node.events().listAfter(session.sessionId(), 0, 10).getLast().data());
        assertThat(stored.content()).containsExactly(new MessageContent.Text("前\uFFFD后"));
    }

    @Test
    void envelopeCarriesTheSessionScopeAndTypedData() {
        AppendResult result = node.events()
                .append(EventBatch.leased(
                        session.sessionId(),
                        session.mainThreadId(),
                        lease.turn().turnId(),
                        lease.epoch(),
                        new AgentOutputEvent.Message("rpl_1:text", List.of(new MessageContent.Text("你好")))));

        StoredEvent event = ((AppendResult.Appended) result).events().getFirst();
        assertThat(event.tenantId()).isEqualTo(session.tenantId());
        assertThat(event.projectId()).isEqualTo(session.projectId());
        assertThat(event.turnId()).isEqualTo(lease.turn().turnId());
        assertThat(event.id()).startsWith("evt_");
        assertThat(event.createdAt()).isEqualTo(clock.instant());
        StoredEvent read = node.events().listAfter(session.sessionId(), 0, 1).getFirst();
        assertThat(read.type()).isEqualTo(EventType.AGENT_MESSAGE);
        assertThat(read.data())
                .isEqualTo("{\"content\": [{\"text\": \"你好\", \"type\": \"text\"}], \"block_id\": \"rpl_1:text\"}");
    }

    @Test
    void statusEventsMoveSessionAndThreadStatusInTheSameTransaction() {
        appendLeased(new SessionEvent.StatusRunning());
        assertThat(status("sessions", session.sessionId())).isEqualTo("running");
        assertThat(status("threads", session.mainThreadId())).isEqualTo("running");

        appendLeased(new SessionEvent.StatusRequiresAction(List.of(new PendingAction(
                "act_1", PendingAction.Type.TOOL_CONFIRMATION, "toolu_1", "write_file", Map.of(), null))));
        assertThat(status("sessions", session.sessionId())).isEqualTo("requires_action");

        appendLeased(new SessionEvent.StatusIdle(StopReason.END_TURN));
        assertThat(status("sessions", session.sessionId())).isEqualTo("idle");
        assertThat(status("threads", session.mainThreadId())).isEqualTo("idle");
    }

    @Test
    void missingSessionIsReportedAndNothingIsWritten() {
        assertThatThrownBy(() -> node.events().append(EventBatch.unfenced("ses_missing", null, List.of(userMessage()))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void eventSinkWritesOffTheReactorThreadWithTheContextEpoch() {
        PlatformContext context = session.context().toBuilder()
                .threadId(session.mainThreadId())
                .turnId(lease.turn().turnId())
                .leaseEpoch(lease.epoch())
                .build();
        String childThread = session.mainThreadId();

        // 从 Reactor 的 parallel 线程发起：JDBC 必须经 Blocking 调度到虚拟线程，否则 BlockHound 报错
        StepVerifier.create(Mono.just(1)
                        .publishOn(Schedulers.parallel())
                        .flatMap(ignored ->
                                node.eventSink().append(childThread, new SessionEvent.ThreadStatusRunning(childThread)))
                        .contextWrite(PlatformContexts.write(context)))
                .verifyComplete();
        StepVerifier.create(Mono.just(1)
                        .publishOn(Schedulers.parallel())
                        .flatMap(ignored -> node.eventSink()
                                .append(childThread, new StreamEvent.EventDelta("b", StreamEvent.BlockKind.TEXT, "x")))
                        .contextWrite(PlatformContexts.write(context)))
                .verifyComplete();
        StepVerifier.create(Mono.just(1)
                        .publishOn(Schedulers.parallel())
                        .flatMap(ignored -> node.eventSink()
                                .append(
                                        childThread,
                                        new SessionEvent.ThreadStatusIdle(childThread, StopReason.END_TURN)))
                        .contextWrite(PlatformContexts.write(context.toBuilder()
                                .leaseEpoch(lease.epoch() + 1)
                                .build())))
                .expectError(LeaseLostException.class)
                .verify();

        List<StoredEvent> events = node.events().listAfter(session.sessionId(), 0, 100);
        assertThat(events).extracting(StoredEvent::type).containsExactly(EventType.SESSION_THREAD_STATUS_RUNNING);
        assertThat(events.getFirst().turnId()).isEqualTo(lease.turn().turnId());
    }

    private void appendLeased(EngineEvent event) {
        assertThat(node.events()
                        .append(EventBatch.leased(
                                session.sessionId(),
                                session.mainThreadId(),
                                lease.turn().turnId(),
                                lease.epoch(),
                                event)))
                .isInstanceOf(AppendResult.Appended.class);
    }

    private static UserEvent.Message userMessage() {
        return new UserEvent.Message(List.of(new MessageContent.Text("API 写入")), UserEvent.Delivery.QUEUE);
    }

    private static String status(String table, String id) {
        return DbAssertions.list("SELECT status FROM rt." + table + " WHERE id = ?", String.class, id)
                .getFirst();
    }
}
