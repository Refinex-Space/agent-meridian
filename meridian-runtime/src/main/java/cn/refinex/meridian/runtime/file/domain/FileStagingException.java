package cn.refinex.meridian.runtime.file.domain;

import cn.refinex.meridian.engine.spi.error.RetryableTurnException;

/**
 * 轮次开始时文件暂时无法放进工作区（对象存储读取失败），可重试：本轮以 {@code session.error(retryable)} 加
 * {@code status_rescheduled} 置回队列，与沙箱获取失败相同（ADR-0050）。
 */
public class FileStagingException extends RetryableTurnException {

    /**
     * 创建异常。
     *
     * @param detail 面向调用方的说明
     * @param cause 底层错误，只进日志
     */
    public FileStagingException(String detail, Throwable cause) {
        super(FileErrorCode.STAGING_UNAVAILABLE, detail, cause);
    }
}
