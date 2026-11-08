package cn.refinex.meridian.runtime.tooling.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.spi.event.BlobRef;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.InvocationStart;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.Status;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.dispatch.app.ClaimResult;
import cn.refinex.meridian.runtime.dispatch.app.LeaseHandle;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.TestNode;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import cn.refinex.meridian.runtime.tooling.domain.ToolInvocation;
import cn.refinex.meridian.runtime.tooling.infra.ToolInvocationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** 工具账本（{@code ToolLedgerPort} 的数据库实现）：开始与结束都带租约条件，恢复时按轮次读出（架构设计「崩溃恢复」）。 */
class DatabaseToolLedgerIT {

    private final MutableClock clock = MutableClock.at(Instant.parse("2100-01-01T00:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private TestSession session;
    private TestNode node;
    private DatabaseToolLedger ledger;
    private LeaseHandle lease;

    @BeforeEach
    void setUp() {
        RuntimeTestDatabase.truncate();
        node = TestNode.create("node-a", clock);
        session = TestSessions.create(clock);
        ledger = new DatabaseToolLedger(
                new ToolInvocationRepository(RuntimeTestDatabase.dataSource()),
                RuntimeTestDatabase.transactions(),
                new OutboxWriter(RuntimeTestDatabase.dataSource(), "rt", clock),
                meters,
                node.nodeId(),
                clock);
        node.enqueuer().enqueue(session.turn("用工具"));
        lease = ((ClaimResult.Claimed) node.claimer().claim()).lease();
    }

    @Test
    void invocationsAreRecordedUnderTheLeaseAndListedByTurn() {
        String first = block(ledger.recordStart(start("call_1", SideEffect.NON_IDEMPOTENT, "idem-1")), lease.epoch());
        clock.advance(Duration.ofSeconds(1));
        String second = block(ledger.recordStart(start("call_2", SideEffect.NONE, null)), lease.epoch());
        BlobRef ref = new BlobRef("rt/t/s/blobs/x", "text/plain", 12);
        block(ledger.recordFinish(first, Status.SUCCEEDED, ref), lease.epoch());

        List<ToolInvocation> invocations = PlatformContexts.call(
                session.context(), () -> ledger.listByTurn(lease.turn().turnId()));

        assertThat(invocations).extracting(ToolInvocation::id).containsExactly(first, second);
        ToolInvocation done = invocations.getFirst();
        assertThat(done.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(done.sideEffect()).isEqualTo(SideEffect.NON_IDEMPOTENT);
        assertThat(done.idempotencyKey()).isEqualTo("idem-1");
        assertThat(done.leaseEpoch()).isEqualTo(lease.epoch());
        assertThat(done.resultRef()).isEqualTo(ref);
        assertThat(done.inputJson()).contains("\"text\": \"x\"");
        assertThat(done.finishedAt()).isNotNull();
        assertThat(invocations.get(1).status()).as("还在执行的调用恢复时视为状态未知").isEqualTo(Status.RUNNING);
    }

    @Test
    void finishingASideEffectingCallWritesAnAuditRecordAndEveryCallIsCounted() {
        String write = block(
                ledger.recordStart(start("call_w", SideEffect.IDEMPOTENT, session.sessionId() + ":call_w")),
                lease.epoch());
        String read = block(
                ledger.recordStart(start("call_r", SideEffect.NONE, session.sessionId() + ":call_r")), lease.epoch());
        block(ledger.recordFinish(write, Status.SUCCEEDED, null), lease.epoch());
        block(ledger.recordFinish(read, Status.FAILED, null), lease.epoch());

        List<Map<String, Object>> audits = DbAssertions.rows(
                "SELECT payload::text AS payload FROM rt.outbox WHERE topic = 'audit' AND payload ->> 'action' = 'tool.executed'"
                        + " AND payload ->> 'session_id' = ?",
                session.sessionId());
        assertThat(audits)
                .as("只有有副作用的调用写审计")
                .singleElement()
                .satisfies(row -> assertThat((String) row.get("payload"))
                        .contains("\"tool_use_id\": \"call_w\"")
                        .contains("\"side_effect\": \"idempotent\"")
                        .contains("\"status\": \"succeeded\"")
                        .contains("\"idempotency_key\": \"" + session.sessionId() + ":call_w\""));
        assertThat(meters.get("meridian.tool.calls")
                        .tags("tool", "tool", "state", "succeeded")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.get("meridian.tool.calls")
                        .tags("tool", "tool", "state", "failed")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void writesWithAStaleEpochAreRejectedAndUnknownRecordsAreNotFound() {
        String started = block(ledger.recordStart(start("call_1", SideEffect.IDEMPOTENT, null)), lease.epoch());

        assertThatThrownBy(() ->
                        block(ledger.recordStart(start("call_2", SideEffect.IDEMPOTENT, null)), lease.epoch() + 1))
                .isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> block(ledger.recordFinish(started, Status.SUCCEEDED, null), lease.epoch() + 1))
                .isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> block(ledger.recordFinish("tiv_missing", Status.FAILED, null), lease.epoch()))
                .isInstanceOf(NotFoundException.class);
        assertThat(PlatformContexts.call(
                        session.context(), () -> ledger.listByTurn(lease.turn().turnId())))
                .singleElement()
                .satisfies(i -> assertThat(i.status()).isEqualTo(Status.RUNNING));
    }

    private InvocationStart start(String toolUseId, SideEffect sideEffect, String idempotencyKey) {
        return new InvocationStart(
                session.mainThreadId(), toolUseId, "tool", sideEffect, idempotencyKey, Map.of("text", "x"));
    }

    private <T> T block(Mono<T> mono, long epoch) {
        PlatformContext context = session.context().toBuilder()
                .threadId(session.mainThreadId())
                .turnId(lease.turn().turnId())
                .leaseEpoch(epoch)
                .build();
        return mono.contextWrite(PlatformContexts.write(context)).block(Duration.ofSeconds(10));
    }
}
