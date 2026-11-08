package cn.refinex.meridian.runtime.modelrouting.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link VectorModelService} 的重试退避：与 {@code RoutedModel} 首包之前的规则相同（架构设计「模型调用」）。 */
class VectorModelServiceTest {

    @Test
    void backoffDoublesAndHonoursRetryAfterUpToTheInPlaceLimit() {
        assertThat(VectorModelService.backoff(0, Optional.empty())).contains(Duration.ofMillis(500));
        assertThat(VectorModelService.backoff(1, Optional.empty())).contains(Duration.ofSeconds(1));
        assertThat(VectorModelService.backoff(0, Optional.of(Duration.ofSeconds(3))))
                .contains(Duration.ofSeconds(3));
        assertThat(VectorModelService.backoff(1, Optional.of(Duration.ofMillis(100))))
                .as("取较大者")
                .contains(Duration.ofSeconds(1));
        assertThat(VectorModelService.backoff(0, Optional.of(Duration.ofSeconds(11))))
                .as("要求等待超过 10 秒时不原地重试")
                .isEmpty();
    }
}
