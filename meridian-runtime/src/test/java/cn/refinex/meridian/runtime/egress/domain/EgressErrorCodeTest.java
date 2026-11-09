package cn.refinex.meridian.runtime.egress.domain;

import static org.assertj.core.api.Assertions.assertThatCode;

import cn.refinex.meridian.common.error.ErrorCodes;
import org.junit.jupiter.api.Test;

/** {@link EgressErrorCode}：沙箱出网授权的错误码（common 的约定：码值格式、4xx/5xx 状态、不重复）。 */
class EgressErrorCodeTest {

    @Test
    void codesFollowTheConvention() {
        assertThatCode(() -> ErrorCodes.verify(EgressErrorCode.values())).doesNotThrowAnyException();
    }
}
