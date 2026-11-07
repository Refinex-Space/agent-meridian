package cn.refinex.meridian.engine.spi.error;

import cn.refinex.meridian.common.error.MeridianException;

/**
 * 预算预扣失败：租户、项目、API Key、会话四级预算中任一级的硬阈值不足，见架构设计「计量与预算」「模型调用」。
 *
 * <p>不重试、不切换备用模型；本轮先输出 {@code session.error}（{@code retry_status=terminal}），再以
 * {@code stop_reason=budget_exceeded} 结束（架构设计「状态机」）。
 */
public class BudgetExceededException extends MeridianException {

    /**
     * 创建预算不足异常。
     *
     * @param detail 面向调用方的说明，例如哪一级预算不足；不含金额以外的内部信息
     */
    public BudgetExceededException(String detail) {
        super(ModelErrorCode.BUDGET_EXCEEDED, detail);
    }
}
