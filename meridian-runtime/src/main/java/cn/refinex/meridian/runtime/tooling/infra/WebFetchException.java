package cn.refinex.meridian.runtime.tooling.infra;

/**
 * {@code web_fetch} 抓取失败（HTTP 错误、不支持的内容类型、重定向过多、超时、连接失败）。消息是给模型看的英文说明，{@code web_fetch}
 * 工具原样放进错误结果；不含请求头与内部地址以外的信息。被 SSRF 防护拒绝的情况用 {@code ForbiddenException}。
 */
public final class WebFetchException extends RuntimeException {

    /**
     * 创建异常。
     *
     * @param message 给模型看的英文说明
     */
    public WebFetchException(String message) {
        super(message, null, false, false);
    }

    /**
     * 创建带原因的异常。
     *
     * @param message 给模型看的英文说明
     * @param cause 原因，只进日志
     */
    public WebFetchException(String message, Throwable cause) {
        super(message, cause, false, false);
    }
}
