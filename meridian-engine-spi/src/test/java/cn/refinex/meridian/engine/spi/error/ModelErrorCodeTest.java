package cn.refinex.meridian.engine.spi.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cn.refinex.meridian.common.error.ErrorCodes;
import org.junit.jupiter.api.Test;

/** {@link ModelErrorCode} 的码值格式与唯一性，以及异常携带的错误码。 */
class ModelErrorCodeTest {

    @Test
    void codesAreWellFormedAndUnique() {
        assertThatCode(() -> ErrorCodes.verify(ModelErrorCode.values())).doesNotThrowAnyException();
        assertThat(ModelErrorCode.values()).allSatisfy(c -> assertThat(c.code()).startsWith("model."));
    }

    @Test
    void exceptionsCarryTheirCodesAndRetryability() {
        assertThat(new BudgetExceededException("会话预算不足").errorCode()).isEqualTo(ModelErrorCode.BUDGET_EXCEEDED);
        RateLimitTimeoutException timeout = new RateLimitTimeoutException("等待 30 秒仍未拿到配额");
        assertThat(timeout).isInstanceOf(RetryableTurnException.class);
        assertThat(timeout.errorCode()).isEqualTo(ModelErrorCode.RATE_LIMIT_TIMEOUT);
    }
}
