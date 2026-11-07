package cn.refinex.meridian.common.error;

import java.util.List;

/**
 * 输入没有通过校验。只在系统边界抛出：HTTP 请求、外部 API 返回、模型输出、配置（编码规范「错误处理」）。
 */
public class ValidationException extends MeridianException {

    private final transient List<FieldViolation> violations;

    /**
     * 使用通用码 {@link CommonErrorCode#VALIDATION_FAILED}，不区分字段。
     *
     * @param detail 面向调用方的具体说明
     */
    public ValidationException(String detail) {
        this(CommonErrorCode.VALIDATION_FAILED, detail, List.of());
    }

    /**
     * 使用指定错误码，不区分字段。
     *
     * @param errorCode 错误码，HTTP 状态应为 400
     * @param detail 面向调用方的具体说明
     */
    public ValidationException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, List.of());
    }

    /**
     * 使用指定错误码并列出违规字段。
     *
     * @param errorCode 错误码，HTTP 状态应为 400
     * @param detail 面向调用方的具体说明
     * @param violations 违规字段，映射为 problem+json 的 {@code errors}
     */
    public ValidationException(ErrorCode errorCode, String detail, List<FieldViolation> violations) {
        super(errorCode, detail);
        this.violations = List.copyOf(violations);
    }

    /**
     * 返回违规字段。
     *
     * @return 违规字段，没有时为空列表
     */
    public List<FieldViolation> violations() {
        return violations;
    }
}
