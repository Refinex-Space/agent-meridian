package cn.refinex.meridian.common.error;

/**
 * 资源不存在。跨租户、跨项目访问也抛它而不是权限不足，避免泄露资源是否存在（架构设计「租户隔离」）。
 */
public class NotFoundException extends MeridianException {

    /**
     * 使用通用码 {@link CommonErrorCode#NOT_FOUND}。
     *
     * @param detail 面向调用方的具体说明
     */
    public NotFoundException(String detail) {
        this(CommonErrorCode.NOT_FOUND, detail);
    }

    /**
     * 使用领域错误码，例如 {@code session.not_found}。
     *
     * @param errorCode 领域错误码，HTTP 状态应为 404
     * @param detail 面向调用方的具体说明
     */
    public NotFoundException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}
