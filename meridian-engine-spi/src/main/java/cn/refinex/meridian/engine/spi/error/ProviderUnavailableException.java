package cn.refinex.meridian.engine.spi.error;

/**
 * 供应商暂时不可用：连接失败、5xx、429，见架构设计「模型调用」中的错误处理。
 *
 * <p>首包之前出现时，{@code RoutedModel} 已重试 2 次并在配置了备用模型时切换过；首包之后出现时不重试、不切换。
 * 两种情况都可重试：本轮以 {@code session.error}（{@code retry_status=retryable}）结束并重新排队（ADR-0026）。
 */
public class ProviderUnavailableException extends RetryableTurnException {

    /**
     * 创建供应商不可用异常。
     *
     * @param detail 面向调用方的说明；不含端点、凭据与供应商返回的原文
     * @param cause 底层错误，只进日志
     */
    public ProviderUnavailableException(String detail, Throwable cause) {
        super(ModelErrorCode.PROVIDER_UNAVAILABLE, detail, cause);
    }
}
