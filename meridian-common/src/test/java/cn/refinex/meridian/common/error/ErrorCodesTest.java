package cn.refinex.meridian.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ErrorCodesTest {

    @Test
    void commonErrorCodesFollowTheConvention() {
        assertThatCode(() -> ErrorCodes.verify(CommonErrorCode.values())).doesNotThrowAnyException();
    }

    @Test
    void typeIsStableUrnDerivedFromCode() {
        assertThat(CommonErrorCode.NOT_FOUND.type().toString()).isEqualTo("urn:meridian:error:resource.not_found");
    }

    @Test
    void malformedCodeIsRejected() {
        assertThatThrownBy(() -> ErrorCodes.verify(code("Session.NotFound", 404)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Session.NotFound");
        assertThatThrownBy(() -> ErrorCodes.verify(code("session", 404))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonErrorStatusIsRejected() {
        assertThatThrownBy(() -> ErrorCodes.verify(code("session.created", 201)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateCodeIsRejected() {
        assertThatThrownBy(() -> ErrorCodes.verify(code("session.not_found", 404), code("session.not_found", 410)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void exceptionsCarryCodeAndDetail() {
        MeridianException e = new NotFoundException("会话 ses_x 不存在");

        assertThat(e.errorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(e.detail()).isEqualTo("会话 ses_x 不存在");
        assertThat(new LeaseLostException("thr_x").errorCode().code()).isEqualTo("turn.lease_lost");
    }

    private static ErrorCode code(String code, int status) {
        return new ErrorCode() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public int status() {
                return status;
            }

            @Override
            public String title() {
                return "标题";
            }
        };
    }
}
