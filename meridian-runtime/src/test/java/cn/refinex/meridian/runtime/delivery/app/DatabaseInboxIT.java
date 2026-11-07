package cn.refinex.meridian.runtime.delivery.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.spi.engine.ThreadRef;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.runtime.delivery.domain.InboxEntry;
import cn.refinex.meridian.runtime.delivery.infra.InboxRepository;
import cn.refinex.meridian.runtime.dispatch.app.ClaimResult;
import cn.refinex.meridian.runtime.dispatch.app.LeaseHandle;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.TestNode;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 收件箱（{@code InboxPort} 的数据库实现）：steer 的带租约消费、轮次结束时合并待投递消息、按 {@code consumed_epoch} 重新投递
 * （架构设计「消息投递」「崩溃恢复」）。
 */
class DatabaseInboxIT {

    private final MutableClock clock = MutableClock.at(Instant.parse("2100-01-01T00:00:00Z"));
    private TestNode node;
    private TestSession session;
    private DatabaseInbox inbox;
    private LeaseHandle lease;

    @BeforeEach
    void setUp() {
        RuntimeTestDatabase.truncate();
        node = TestNode.create("node-a", clock);
        session = TestSessions.create(clock);
        inbox = new DatabaseInbox(
                new InboxRepository(RuntimeTestDatabase.dataSource()),
                RuntimeTestDatabase.transactions(),
                node.nodeId(),
                clock);
        node.enqueuer().enqueue(session.turn("第一轮"));
        lease = ((ClaimResult.Claimed) node.claimer().claim()).lease();
    }

    @Test
    void steerMessagesAreConsumedInArrivalOrderUnderTheLeaseAndOnlyOnce() {
        String queued = add("排队的", Delivery.QUEUE);
        String first = add("先到的 steer", Delivery.STEER);
        clock.advance(Duration.ofMillis(5));
        String second = add("后到的 steer", Delivery.STEER);

        List<InboundMessage> consumed = consumeSteer(lease.epoch());

        assertThat(consumed).extracting(InboundMessage::eventId).containsExactly(first, second);
        assertThat(consumed.getFirst().content()).containsExactly(new MessageContent.Text("先到的 steer"));
        assertThat(consumeSteer(lease.epoch())).as("已消费的不会再取出").isEmpty();
        assertThat(DbAssertions.list(
                        "SELECT consumed_epoch FROM rt.inbox_messages WHERE event_id IN (?, ?)",
                        Long.class,
                        first,
                        second))
                .containsOnly(lease.epoch());
        assertThat(DbAssertions.list("SELECT status FROM rt.inbox_messages WHERE event_id = ?", String.class, queued))
                .containsExactly("pending");
    }

    @Test
    void consumingSteerWithoutTheLeaseIsRejectedAndChangesNothing() {
        String steer = add("steer", Delivery.STEER);

        assertThatThrownBy(() -> consumeSteer(lease.epoch() + 1)).isInstanceOf(LeaseLostException.class);
        assertThat(DbAssertions.list("SELECT status FROM rt.inbox_messages WHERE event_id = ?", String.class, steer))
                .containsExactly("pending");
    }

    @Test
    void pendingMessagesMergeIntoTheNextTurnAndUninjectedSteerDegradesToQueue() {
        String queued = add("排队的", Delivery.QUEUE);
        clock.advance(Duration.ofMillis(5));
        String steer = add("没来得及注入的 steer", Delivery.STEER);

        List<InboundMessage> merged =
                PlatformContexts.call(session.context(), () -> inbox.takePending(session.mainThreadId(), "trn_next"));

        assertThat(merged).extracting(InboundMessage::eventId).containsExactly(queued, steer);
        assertThat(DbAssertions.list(
                        "SELECT consumed_turn_id || ':' || coalesce(consumed_epoch::text, '-') FROM rt.inbox_messages"
                                + " WHERE thread_id = ?",
                        String.class,
                        session.mainThreadId()))
                .as("合并进下一轮的消息随输入重放，不记录执行 epoch")
                .containsOnly("trn_next:-");
    }

    @Test
    void onlySteerConsumedByTheFailedEpochIsRedelivered() {
        String failed = add("失败那次执行消费的", Delivery.STEER);
        consumeSteer(lease.epoch());
        String queued = add("排队的", Delivery.QUEUE);
        PlatformContexts.run(session.context(), () -> inbox.takePending(session.mainThreadId(), "trn_next"));

        int redelivered = PlatformContexts.call(
                session.context(), () -> inbox.redeliverSteer(lease.turn().turnId(), lease.epoch()));

        assertThat(redelivered).isEqualTo(1);
        assertThat(DbAssertions.list("SELECT status FROM rt.inbox_messages WHERE event_id = ?", String.class, failed))
                .containsExactly("pending");
        assertThat(DbAssertions.list("SELECT status FROM rt.inbox_messages WHERE event_id = ?", String.class, queued))
                .as("合并进下一轮的 queue 消息不受影响")
                .containsExactly("consumed");
        assertThat(PlatformContexts.call(
                        session.context(),
                        () -> inbox.redeliverSteer(lease.turn().turnId(), 99)))
                .isZero();
    }

    private String add(String text, Delivery delivery) {
        AppendResult appended = node.events()
                .append(EventBatch.unfenced(
                        session.sessionId(),
                        session.mainThreadId(),
                        List.of(new UserEvent.Message(List.of(new MessageContent.Text(text)), delivery))));
        String eventId = ((AppendResult.Appended) appended).events().getFirst().id();
        PlatformContexts.run(
                session.context(),
                () -> inbox.add(new InboxEntry(session.sessionId(), session.mainThreadId(), eventId, delivery)));
        return eventId;
    }

    private List<InboundMessage> consumeSteer(long epoch) {
        return inbox.consumeSteer(
                        new ThreadRef(session.sessionId(), session.mainThreadId()),
                        lease.turn().turnId(),
                        epoch)
                .contextWrite(PlatformContexts.write(session.context()))
                .block(Duration.ofSeconds(10));
    }
}
