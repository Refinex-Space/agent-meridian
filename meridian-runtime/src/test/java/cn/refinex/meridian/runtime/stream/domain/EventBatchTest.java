package cn.refinex.meridian.runtime.stream.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StreamEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 写入批次与租约条件：只持久化可持久化的事件，租约条件必须带有效的轮次与 epoch。 */
class EventBatchTest {

    @Test
    void leasedBatchCarriesTheTurnLease() {
        EventBatch batch = EventBatch.leased("ses_1", "thr_1", "trn_1", 3, new SessionEvent.StatusRunning());

        assertThat(batch.fence()).isEqualTo(new WriteFence.TurnLease("trn_1", 3));
        assertThat(batch.turnId()).isEqualTo("trn_1");
        assertThat(batch.events()).hasSize(1);
    }

    @Test
    void unfencedBatchHasNoTurn() {
        EventBatch batch = EventBatch.unfenced("ses_1", "thr_1", List.of(new SessionEvent.StatusRunning()));

        assertThat(batch.fence()).isEqualTo(WriteFence.none());
        assertThat(batch.turnId()).isNull();
    }

    @Test
    void streamOnlyAndEmptyBatchesAreRejected() {
        assertThatThrownBy(() -> EventBatch.unfenced("ses_1", "thr_1", List.of(new StreamEvent.Ping())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ping");
        assertThatThrownBy(() -> EventBatch.unfenced("ses_1", "thr_1", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventBatch(null, null, null, WriteFence.none(), List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void leaseFenceNeedsATurnAndAPositiveEpoch() {
        assertThatThrownBy(() -> WriteFence.lease("trn_1", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteFence.lease(null, 1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void appendedReportsTheLastSeq() {
        Instant now = Instant.parse("2100-01-01T00:00:00Z");
        StoredEvent first = new StoredEvent(
                "ses_1", 7, "evt_1", "ten_1", "prj_1", "thr_1", null, EventType.USER_MESSAGE, "{}", now);
        StoredEvent second = new StoredEvent(
                "ses_1", 8, "evt_2", "ten_1", "prj_1", "thr_1", null, EventType.USER_MESSAGE, "{}", now);

        assertThat(new AppendResult.Appended(List.of(first, second)).lastSeq()).isEqualTo(8);
    }
}
