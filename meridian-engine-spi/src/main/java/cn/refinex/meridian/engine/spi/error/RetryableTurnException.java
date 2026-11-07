package cn.refinex.meridian.engine.spi.error;

import cn.refinex.meridian.common.error.ErrorCode;
import cn.refinex.meridian.common.error.MeridianException;

/**
 * 可重试的错误的基类：轮次应置回队列、{@code recovery_attempt} 加一，由其他 Worker 以恢复轮续跑，见架构设计
 * 「一轮执行的生命周期」中"置回队列的三种情况"。
 *
 * <p>内核把它翻译为 {@code session.error}（{@code retry_status=retryable}）加 {@code session.status_rescheduled}
 * （原因 {@code retryable_error}）；恢复次数是否用尽由 Worker 在认领时判断（ADR-0026）。限流等待超时
 * （{@link RateLimitTimeoutException}）与模型层判定可重试的供应商错误都继承它。
 */
public abstract class RetryableTurnException extends MeridianException {

    /**
     * 创建可重试的错误。
     *
     * @param errorCode 错误码
     * @param detail 面向调用方的说明
     */
    protected RetryableTurnException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    /**
     * 创建带内部原因的可重试错误。
     *
     * @param errorCode 错误码
     * @param detail 面向调用方的说明
     * @param cause 内部原因，只进日志
     */
    protected RetryableTurnException(ErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause);
    }
}
