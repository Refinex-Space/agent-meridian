package cn.refinex.meridian.engine.spi.error;

/**
 * 限流等待超时：按"供应商账号加模型"的 RPM 与 TPM 令牌桶等待超过 30 秒，见架构设计「模型调用」。
 *
 * <p>可重试：本轮以 {@code session.error}（{@code retry_status=retryable}）结束并重新排队。
 */
public class RateLimitTimeoutException extends RetryableTurnException {

    /**
     * 创建限流等待超时异常。
     *
     * @param detail 面向调用方的说明，例如等待了多久；不含供应商账号与凭据
     */
    public RateLimitTimeoutException(String detail) {
        super(ModelErrorCode.RATE_LIMIT_TIMEOUT, detail);
    }
}
