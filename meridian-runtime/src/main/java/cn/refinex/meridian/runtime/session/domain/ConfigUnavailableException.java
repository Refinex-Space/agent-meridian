package cn.refinex.meridian.runtime.session.domain;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.engine.spi.error.RetryableTurnException;

/**
 * control-plane 暂时读不到智能体配置（连接失败、5xx，ADR-0047）。轮次中遇到时可重试：内核以 {@code session.error(retryable)} 加
 * {@code rescheduled} 结束本轮；创建会话时是 503 {@code service.unavailable}。
 */
public final class ConfigUnavailableException extends RetryableTurnException {

    /**
     * 创建异常。
     *
     * @param detail 面向调用方的说明
     * @param cause 原因
     */
    public ConfigUnavailableException(String detail, Throwable cause) {
        super(CommonErrorCode.SERVICE_UNAVAILABLE, detail);
        if (cause != null) {
            initCause(cause);
        }
    }
}
