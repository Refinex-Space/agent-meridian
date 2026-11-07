package cn.refinex.meridian.runtime.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.SessionEvent.RescheduleReason;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 调度的值对象：置回队列的三种原因、轮次结束状态、入队参数与认领结果的规则。 */
class DispatchDomainTest {

    @Test
    void requeueReasonsFollowTheArchitectureTable() {
        assertThat(RequeueReason.LEASE_EXPIRED.countsAsRecovery()).isTrue();
        assertThat(RequeueReason.RETRYABLE_ERROR.countsAsRecovery()).isTrue();
        assertThat(RequeueReason.NODE_SHUTDOWN.countsAsRecovery()).isFalse();
        assertThat(RequeueReason.LEASE_EXPIRED.eventReason()).isEqualTo(RescheduleReason.LEASE_EXPIRED);
        assertThat(RequeueReason.RETRYABLE_ERROR.eventReason()).isEqualTo(RescheduleReason.RETRYABLE_ERROR);
        assertThat(RequeueReason.NODE_SHUTDOWN.eventReason()).isEqualTo(RescheduleReason.NODE_SHUTDOWN);
    }

    @Test
    void turnOutcomesMatchTheTurnStatusColumn() {
        assertThat(List.of(TurnOutcome.values()))
                .extracting(TurnOutcome::value)
                .containsExactly("completed", "interrupted", "failed", "awaiting_action");
    }

    @Test
    void newTurnRequiresScopeThreadAndInput() {
        TurnInput input = new TurnInput.RecoveryOnly(new RecoveryNote(List.of()));

        NewTurn turn = NewTurn.of("ten_1", "prj_1", "ses_1", "thr_1", input);

        assertThat(turn.priority()).isZero();
        assertThat(turn.input().kind()).isEqualTo("recovery");
        assertThatThrownBy(() -> NewTurn.of("ten_1", "prj_1", "ses_1", null, input))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewTurn("ten_1", "prj_1", "ses_1", "thr_1", null, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void claimedTurnIsARecoveryOnlyAfterBeingRequeuedWithAnAttempt() {
        Instant now = Instant.parse("2100-01-01T00:00:00Z");
        ClaimedTurn first = new ClaimedTurn(
                "trn_1", "ten_1", "prj_1", "ses_1", "thr_1", "messages", 1, 1, 0, null, now, now, now.plusSeconds(30));
        ClaimedTurn recovered = new ClaimedTurn(
                "trn_1", "ten_1", "prj_1", "ses_1", "thr_1", "messages", 2, 2, 1, null, now, now, now.plusSeconds(30));

        assertThat(first.isRecovery()).isFalse();
        assertThat(recovered.isRecovery()).isTrue();
        assertThat(new NodeId("node-a")).hasToString("node-a");
    }
}
