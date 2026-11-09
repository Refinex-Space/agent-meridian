package cn.refinex.meridian.engine.spi.error;

import static org.assertj.core.api.Assertions.assertThatCode;

import cn.refinex.meridian.common.error.ErrorCodes;
import org.junit.jupiter.api.Test;

/** {@link SandboxErrorCode}：沙箱的错误码（common 的约定：码值格式、4xx/5xx 状态、不重复）。 */
class SandboxErrorCodeTest {

    @Test
    void codesFollowTheConvention() {
        assertThatCode(() -> ErrorCodes.verify(SandboxErrorCode.values())).doesNotThrowAnyException();
    }
}
