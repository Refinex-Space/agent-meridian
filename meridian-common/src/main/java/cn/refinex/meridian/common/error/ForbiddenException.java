package cn.refinex.meridian.common.error;

/**
 * 权限不足：调用方已认证，但其作用域或角色不允许该操作（架构设计「授权」）。
 */
public class ForbiddenException extends MeridianException {

    /**
     * 使用通用码 {@link CommonErrorCode#FORBIDDEN}。
     *
     * @param detail 面向调用方的具体说明，不写出所需权限之外的内部信息
     */
    public ForbiddenException(String detail) {
        this(CommonErrorCode.FORBIDDEN, detail);
    }

    /**
     * 使用领域错误码。
     *
     * @param errorCode 领域错误码，HTTP 状态应为 403
     * @param detail 面向调用方的具体说明
     */
    public ForbiddenException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}
