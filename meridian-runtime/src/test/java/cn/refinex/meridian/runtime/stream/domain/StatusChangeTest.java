package cn.refinex.meridian.runtime.stream.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.spi.event.AgentOutputEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.runtime.session.domain.SessionStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link StatusChange}：状态事件推进会话与线程状态，其他事件不改状态。 */
class StatusChangeTest {

    @Test
    void sessionStatusEventsMoveSessionAndItsThread() {
        assertThat(StatusChange.of("thr_1", new SessionEvent.StatusRunning()))
                .contains(new StatusChange(SessionStatus.RUNNING, "thr_1", SessionStatus.RUNNING));
        assertThat(StatusChange.of("thr_1", new SessionEvent.StatusIdle(StopReason.END_TURN)))
                .contains(new StatusChange(SessionStatus.IDLE, "thr_1", SessionStatus.IDLE));
        assertThat(StatusChange.of(
                        "thr_1", new SessionEvent.StatusRescheduled(SessionEvent.RescheduleReason.LEASE_EXPIRED)))
                .contains(new StatusChange(SessionStatus.RESCHEDULED, "thr_1", SessionStatus.RESCHEDULED));
        assertThat(StatusChange.of(null, new SessionEvent.StatusIdle(StopReason.ERROR)))
                .contains(new StatusChange(SessionStatus.IDLE, null, null));
    }

    @Test
    void terminationMovesOnlyTheSession() {
        assertThat(StatusChange.of("thr_1", new SessionEvent.StatusTerminated(SessionEvent.TerminationReason.ARCHIVED)))
                .contains(new StatusChange(SessionStatus.TERMINATED, null, null));
    }

    @Test
    void threadStatusEventsMoveTheChildThreadNamedInTheData() {
        assertThat(StatusChange.of("thr_1", new SessionEvent.ThreadStatusRunning("thr_2")))
                .contains(new StatusChange(null, "thr_2", SessionStatus.RUNNING));
        assertThat(StatusChange.of("thr_1", new SessionEvent.ThreadStatusIdle("thr_2", StopReason.END_TURN)))
                .contains(new StatusChange(null, "thr_2", SessionStatus.IDLE));
    }

    @Test
    void otherEventsDoNotChangeStatus() {
        assertThat(StatusChange.of("thr_1", new AgentOutputEvent.Thinking("b1", "x")))
                .isEmpty();
        assertThat(StatusChange.of("thr_1", new SessionEvent.MessageConsumed(List.of("evt_1"))))
                .isEmpty();
    }
}
