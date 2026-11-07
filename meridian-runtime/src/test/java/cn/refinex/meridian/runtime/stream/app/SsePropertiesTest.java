package cn.refinex.meridian.runtime.stream.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** {@link SseProperties} 的默认值来自架构设计「SSE 协议」：对账 5 秒、ping 15 秒、单连接最长 60 分钟。 */
class SsePropertiesTest {

    @Test
    void defaultsFollowTheArchitecture() {
        SseProperties properties = new Binder(
                        new MapConfigurationPropertySource(Map.of("meridian.runtime.sse.read-batch", "500")))
                .bindOrCreate("meridian.runtime.sse", SseProperties.class);

        assertThat(properties.reconcileInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.pingInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.maxConnectionDuration()).isEqualTo(Duration.ofMinutes(60));
        assertThat(properties.pollInterval()).isEqualTo(Duration.ofMillis(100));
        assertThat(properties.subscriberBuffer()).isEqualTo(10_000);
    }

    @Test
    void streamEntryIdsCompareByTimeThenSequence() {
        assertThat(SessionStreamHub.compareIds("1730959789123-0", "1730959789123-1"))
                .isNegative();
        assertThat(SessionStreamHub.compareIds("1730959789124-0", "1730959789123-9"))
                .isPositive();
        assertThat(SessionStreamHub.compareIds("0-0", "0-0")).isZero();
        assertThat(SessionStreamHub.compareIds("99-10", "99-9")).isPositive();
    }
}
