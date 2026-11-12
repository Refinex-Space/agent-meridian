package cn.refinex.meridian.engine.spi.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link ProviderRetryPolicy}：首包之前的重试规则（架构设计「模型调用」），{@code RoutedModel} 与 {@code VectorModelService} 共用。 */
class ProviderRetryPolicyTest {

    private static final ProviderRetryPolicy POLICY = ProviderRetryPolicy.DEFAULT;

    @Test
    void backoffDoublesAndHonoursRetryAfterUpToTheInPlaceLimit() {
        assertThat(POLICY.backoff(0, Optional.empty())).contains(Duration.ofMillis(500));
        assertThat(POLICY.backoff(1, Optional.empty())).contains(Duration.ofSeconds(1));
        assertThat(POLICY.backoff(0, Optional.of(Duration.ofSeconds(3)))).contains(Duration.ofSeconds(3));
        assertThat(POLICY.backoff(1, Optional.of(Duration.ofMillis(100))))
                .as("取较大者")
                .contains(Duration.ofSeconds(1));
        assertThat(POLICY.backoff(0, Optional.of(Duration.ofSeconds(11))))
                .as("要求等待超过 10 秒时不原地重试")
                .isEmpty();
    }

    @Test
    void retriesStopAtTheLimitAndLargeCountsDoNotOverflow() {
        assertThat(POLICY.backoff(2, Optional.empty())).as("重试 2 次后不再重试").isEmpty();
        ProviderRetryPolicy many = new ProviderRetryPolicy(100, Duration.ofMillis(1), Duration.ofDays(1));
        assertThat(many.backoff(64, Optional.empty())).contains(Duration.ofMillis(1L << 16));
    }
}
