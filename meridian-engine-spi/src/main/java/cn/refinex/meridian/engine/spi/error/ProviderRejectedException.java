package cn.refinex.meridian.engine.spi.error;

import cn.refinex.meridian.common.error.MeridianException;

/**
 * 供应商拒绝了请求：认证失败、参数错误等不会因重试而成功的 4xx（429 除外），见架构设计「模型调用」。
 *
 * <p>不重试、不切换备用模型；本轮以 {@code session.error}（{@code retry_status=terminal}）加 {@code stop_reason=error} 结束。
 */
public class ProviderRejectedException extends MeridianException {

    /**
     * 创建供应商拒绝异常。
     *
     * @param detail 面向调用方的说明，例如 HTTP 状态码；不含端点、凭据与供应商返回的原文
     * @param cause 底层错误，只进日志
     */
    public ProviderRejectedException(String detail, Throwable cause) {
        super(ModelErrorCode.PROVIDER_ERROR, detail, cause);
    }
}
