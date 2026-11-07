package cn.refinex.meridian.runtime.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@link DispatchProperties}：默认值与架构设计「租约」「租约回收」一致，非法组合在启动时失败。 */
class DispatchPropertiesTest {

    @Test
    void defaultsMatchTheArchitecture() {
        DispatchProperties defaults = DispatchProperties.defaults();

        assertThat(defaults.leaseTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.renewInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.renewFailureTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(defaults.reapInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.pollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaults.nodeId()).isNull();
    }

    @Test
    void renewalMustHappenWithinTheLease() {
        assertThatThrownBy(() -> new DispatchProperties(
                        null,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1),
                        20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DispatchProperties.defaults().withDefaultTenantMaxRunning(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
