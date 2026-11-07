package cn.refinex.meridian.common.error;

/**
 * 资源状态与请求冲突，例如重复创建、状态机不允许的迁移、幂等键冲突。
 */
public class ConflictException extends MeridianException {

    /**
     * 使用通用码 {@link CommonErrorCode#CONFLICT}。
     *
     * @param detail 面向调用方的具体说明
     */
    public ConflictException(String detail) {
        this(CommonErrorCode.CONFLICT, detail);
    }

    /**
     * 使用领域错误码。
     *
     * @param errorCode 领域错误码，HTTP 状态应为 409
     * @param detail 面向调用方的具体说明
     */
    public ConflictException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}
