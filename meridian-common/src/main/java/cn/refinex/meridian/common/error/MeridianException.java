package cn.refinex.meridian.common.error;

/**
 * 平台业务异常的基类：携带 {@link ErrorCode} 与面向调用方的 detail，在 HTTP 边界由 {@code security.web}
 * 映射为 problem+json（编码规范「错误处理」）。
 *
 * <p>detail 会原样返回给调用方，不能包含凭据、提示词正文、内部主机名等信息；内部原因放在 cause 中，只进日志。
 */
public class MeridianException extends RuntimeException {

    private final transient ErrorCode errorCode;

    /**
     * 创建平台异常。
     *
     * @param errorCode 错误码
     * @param detail 面向调用方的具体说明
     */
    public MeridianException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    /**
     * 创建带内部原因的平台异常。
     *
     * @param errorCode 错误码
     * @param detail 面向调用方的具体说明
     * @param cause 内部原因，只进日志，不返回给调用方
     */
    public MeridianException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }

    /**
     * 返回错误码。
     *
     * @return 错误码
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 返回面向调用方的具体说明，即 problem+json 的 {@code detail}。
     *
     * @return 具体说明，可为空
     */
    public String detail() {
        return getMessage();
    }
}
