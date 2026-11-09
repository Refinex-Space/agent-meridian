package cn.refinex.meridian.security.servicejwt;

import static org.assertj.core.api.Assertions.assertThatCode;

import cn.refinex.meridian.common.error.ErrorCodes;
import org.junit.jupiter.api.Test;

/** {@link ServiceCredentialErrorCode}：内部接口服务凭据的错误码（common 的约定：码值格式、4xx/5xx 状态、不重复）。 */
class ServiceCredentialErrorCodeTest {

    @Test
    void codesFollowTheConvention() {
        assertThatCode(() -> ErrorCodes.verify(ServiceCredentialErrorCode.values()))
                .doesNotThrowAnyException();
    }
}
