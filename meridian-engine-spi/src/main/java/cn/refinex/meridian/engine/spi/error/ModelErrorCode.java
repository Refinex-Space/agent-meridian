package cn.refinex.meridian.engine.spi.error;

import cn.refinex.meridian.common.error.ErrorCode;

/**
 * 模型调用领域（{@code model.}）的错误码：预算、限流、供应商错误与压缩失败，作为 {@code session.error} 的 {@code code}，
 * 在 OpenAI 兼容直通接口中映射为 problem+json（架构设计「模型调用」「计量与预算」）。
 */
public enum ModelErrorCode implements ErrorCode {
    /** 四级预算任一级的硬阈值不足以预扣本次调用，本轮以 {@code budget_exceeded} 结束。 */
    BUDGET_EXCEEDED("model.budget_exceeded", 429, "预算不足"),
    /** 按"供应商账号加模型"的令牌桶等待超过 30 秒，可重试。 */
    RATE_LIMIT_TIMEOUT("model.rate_limit_timeout", 429, "模型限流等待超时"),
    /** 供应商暂时不可用（连接失败、5xx、429），重试与降级之后仍失败，可重试。 */
    PROVIDER_UNAVAILABLE("model.provider_unavailable", 503, "模型供应商暂时不可用"),
    /** 供应商拒绝了请求（认证失败、参数错误等 4xx），重试不会成功。 */
    PROVIDER_ERROR("model.provider_error", 502, "模型供应商返回错误"),
    /** 压缩器的摘要调用失败，失败的摘要没有替换历史（架构设计「平台中间件」中的 {@code CompactionGuardMiddleware}）。 */
    COMPACTION_FAILED("model.compaction_failed", 500, "上下文压缩失败");

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
