package cn.refinex.meridian.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class MutableClockTest {

    private static final Instant START = Instant.parse("2026-11-07T00:00:00Z");

    @Test
    void advancesOnlyWhenTold() {
        MutableClock clock = MutableClock.at(START);

        assertThat(clock.instant()).isEqualTo(START);
        assertThat(clock.advance(Duration.ofSeconds(30))).isEqualTo(START.plusSeconds(30));
        assertThat(clock.instant()).isEqualTo(START.plusSeconds(30));
    }

    @Test
    void zonedCopySharesTime() {
        MutableClock clock = MutableClock.at(START);
        Clock shanghai = clock.withZone(ZoneId.of("Asia/Shanghai"));

        clock.advance(Duration.ofMinutes(1));

        assertThat(shanghai.instant()).isEqualTo(START.plusSeconds(60));
        assertThat(shanghai.getZone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void cannotAdvanceBackwardsButCanBeSet() {
        MutableClock clock = MutableClock.at(START);

        assertThatThrownBy(() -> clock.advance(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        clock.set(START.minusSeconds(5));
        assertThat(clock.instant()).isEqualTo(START.minusSeconds(5));
    }
}
