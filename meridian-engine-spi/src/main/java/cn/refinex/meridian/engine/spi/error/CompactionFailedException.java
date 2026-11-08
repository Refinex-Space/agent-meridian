package cn.refinex.meridian.engine.spi.error;

import cn.refinex.meridian.common.error.MeridianException;

/**
 * 压缩失败：压缩器的摘要调用出错后返回了失败文本（{@code (Summarization failed ...}），由
 * {@code CompactionGuardMiddleware} 的内层拦截，历史保持压缩前的样子，见架构设计「平台中间件」。
 *
 * <p>本轮如何结束由原因决定：原因是 {@link BudgetExceededException} 时以 {@code budget_exceeded} 结束，是
 * {@link RetryableTurnException} 时重新排队，没有平台原因时以 {@code session.error}（{@code terminal}）结束。事件映射沿原因链查找，
 * 所以原因要挂在 {@link #getCause()} 上。
 */
public class CompactionFailedException extends MeridianException {

    /** 面向调用方的说明，不含摘要文本与供应商信息。 */
    static final String DETAIL = "上下文压缩失败，历史未被替换";

    /**
     * 创建压缩失败异常。
     *
     * @param cause 压缩器内部模型调用的错误；可为空，表示没有拿到原因（例如模型返回了空摘要）
     */
    public CompactionFailedException(Throwable cause) {
        super(ModelErrorCode.COMPACTION_FAILED, DETAIL, cause);
    }
}
