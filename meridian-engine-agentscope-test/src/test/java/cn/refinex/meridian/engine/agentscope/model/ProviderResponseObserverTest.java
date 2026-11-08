package cn.refinex.meridian.engine.agentscope.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** {@code Retry-After} 的两种格式（RFC 9110 第 10.2.3 节）。 */
class ProviderResponseObserverTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void parsesDelaySecondsAndHttpDates() {
        assertThat(ProviderResponseObserver.parseRetryAfter("3", CLOCK)).isEqualTo(Duration.ofSeconds(3));
        assertThat(ProviderResponseObserver.parseRetryAfter(" 0 ", CLOCK)).isEqualTo(Duration.ZERO);
        assertThat(ProviderResponseObserver.parseRetryAfter("Sun, 08 Nov 2026 08:00:05 GMT", CLOCK))
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(ProviderResponseObserver.parseRetryAfter("Sun, 08 Nov 2026 07:59:00 GMT", CLOCK))
                .as("过去的时间视为不用等")
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void missingOrMalformedValuesAreIgnored() {
        assertThat(ProviderResponseObserver.parseRetryAfter(null, CLOCK)).isNull();
        assertThat(ProviderResponseObserver.parseRetryAfter("", CLOCK)).isNull();
        assertThat(ProviderResponseObserver.parseRetryAfter("soon", CLOCK)).isNull();
    }

    @Test
    void recordsStatusAndRetryAfter() {
        ProviderResponseObserver observer = new ProviderResponseObserver();
        assertThat(observer.retryAfter()).isEmpty();

        observer.record(429, "2", CLOCK);

        assertThat(observer.status()).isEqualTo(429);
        assertThat(observer.retryAfter()).contains(Duration.ofSeconds(2));
    }
}
