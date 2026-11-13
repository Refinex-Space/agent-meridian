package cn.refinex.meridian.runtime.session.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class StatsWindowTest {

    @Test
    void windowsAlignToWholeHoursIncludingTheCurrentOne() {
        Instant now = Instant.parse("2026-11-12T10:42:13Z");
        assertThat(StatsWindow.ONE_HOUR.start(now)).isEqualTo(Instant.parse("2026-11-12T10:00:00Z"));
        assertThat(StatsWindow.ONE_DAY.start(now)).isEqualTo(Instant.parse("2026-11-11T11:00:00Z"));
        assertThat(StatsWindow.SEVEN_DAYS.start(now)).isEqualTo(Instant.parse("2026-11-05T11:00:00Z"));
        assertThat(StatsWindow.of("24h")).contains(StatsWindow.ONE_DAY);
        assertThat(StatsWindow.of("2d")).isEmpty();
        assertThat(StatsWindow.RETENTION).isGreaterThan(java.time.Duration.ofHours(168));
    }

    @Test
    void failureRateIsAbsentWithoutTurns() {
        assertThat(new SessionStats.AgentStats("agt_1", 3, 1, 0, 0).failureRate())
                .isNull();
        assertThat(new SessionStats.AgentStats("agt_1", 3, 1, 4, 1).failureRate())
                .isEqualTo(0.25);
    }
}
