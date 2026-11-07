package cn.refinex.meridian.engine.spi.error;

import cn.refinex.meridian.common.error.ErrorCode;

/**
 * 模型调用领域（{@code model.}）的错误码：预算与限流，作为 {@code session.error} 的 {@code code}，
 * 在 OpenAI 兼容直通接口中映射为 problem+json（架构设计「模型调用」「计量与预算」）。
 */
public enum ModelErrorCode implements ErrorCode {
    /** 四级预算任一级的硬阈值不足以预扣本次调用，本轮以 {@code budget_exceeded} 结束。 */
    BUDGET_EXCEEDED("model.budget_exceeded", 429, "预算不足"),
    /** 按"供应商账号加模型"的令牌桶等待超过 30 秒，可重试。 */
    RATE_LIMIT_TIMEOUT("model.rate_limit_timeout", 429, "模型限流等待超时");

    private final String code;
    private final int status;
    private final String title;

    ModelErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

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
        return title;
    }
}
