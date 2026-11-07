package cn.refinex.meridian.runtime.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.engine.spi.event.PendingAction;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 待处理动作的状态取值与等待判断。 */
class PendingActionStatusTest {

    @Test
    void statusValuesRoundTrip() {
        for (PendingActionStatus status : PendingActionStatus.values()) {
            assertThat(PendingActionStatus.of(status.value())).isEqualTo(status);
        }
        assertThatThrownBy(() -> PendingActionStatus.of("cancelled")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyPendingActionsAwaitAResponse() {
        Instant now = Instant.parse("2100-01-01T00:00:00Z");
        StoredPendingAction pending = new StoredPendingAction(
                "act_1",
                "ses_1",
                "thr_1",
                "trn_1",
                PendingAction.Type.TOOL_CONFIRMATION,
                PendingActionStatus.PENDING,
                "toolu_1",
                "write_file",
                null,
                now,
                now,
                null);

        assertThat(pending.isPending()).isTrue();
        assertThat(pending.input()).isEmpty();
        assertThat(new StoredPendingAction(
                                "act_2",
                                "ses_1",
                                "thr_1",
                                "trn_1",
                                PendingAction.Type.TOOL_CONFIRMATION,
                                PendingActionStatus.RESOLVED,
                                "toolu_1",
                                "write_file",
                                null,
                                now,
                                now,
                                now)
                        .isPending())
                .isFalse();
    }
}
