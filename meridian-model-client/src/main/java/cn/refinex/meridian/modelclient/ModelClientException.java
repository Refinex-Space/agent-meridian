package cn.refinex.meridian.modelclient;

import org.springframework.http.HttpStatusCode;

/**
 * runtime 的内部模型接口拒绝了请求或暂时不可用。调用方按 {@link #retryable()} 决定退避重试还是放弃：429（限流、预算）、5xx 与连接失败
 * 可以重试，其余 4xx（模型不存在、种类不符、参数不合法）重试也不会成功。
 */
public final class ModelClientException extends RuntimeException {

    /** 连接失败、超时或响应不完整时的错误码。 */
    public static final String UNAVAILABLE = "service.unavailable";

    private final boolean retryable;
    private final int status;
    private final String code;

    /**
     * 创建异常。
     *
     * @param retryable 是否可以重试
     * @param status HTTP 状态码；没有响应时为 0
     * @param code runtime 返回的错误码（{@code <领域>.<原因>}），例如 {@code model.not_found}
     * @param message 说明
     * @param cause 原因；可为空
     */
    public ModelClientException(boolean retryable, int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.status = status;
        this.code = code;
    }

    static ModelClientException fromResponse(HttpStatusCode status, String code, String detail) {
        String resolved = code != null ? code : status.is5xxServerError() ? UNAVAILABLE : "request.invalid";
        return new ModelClientException(
                EmbeddingClient.retryable(status),
                status.value(),
                resolved,
                "runtime 返回 " + status.value() + "：" + detail,
                null);
    }

    /**
     * 是否可以重试。
     *
     * @return 429、5xx 与连接失败时为 true
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * HTTP 状态码。
     *
     * @return 状态码；没有响应时为 0
     */
    public int status() {
        return status;
    }

    /**
     * 错误码。
     *
     * @return runtime 返回的错误码，或 {@value #UNAVAILABLE}
     */
    public String code() {
        return code;
    }
}
