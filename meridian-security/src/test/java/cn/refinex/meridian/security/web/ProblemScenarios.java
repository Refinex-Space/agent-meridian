package cn.refinex.meridian.security.web;

import cn.refinex.meridian.common.error.ErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.error.ValidationException;
import java.util.List;

/** 两种 Web 栈的测试控制器共用的异常场景。 */
final class ProblemScenarios {

    /** 测试用的领域错误码。 */
    enum SessionErrorCode implements ErrorCode {
        NOT_FOUND("session.not_found", 404, "会话不存在");

        private final String code;
        private final int status;
        private final String title;

        SessionErrorCode(String code, int status, String title) {
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

    private ProblemScenarios() {}

    static RuntimeException failure(String scenario) {
        return switch (scenario) {
            case "not-found" -> new NotFoundException(SessionErrorCode.NOT_FOUND, "会话 ses_x 不存在");
            case "invalid" ->
                new ValidationException(
                        cn.refinex.meridian.common.error.CommonErrorCode.VALIDATION_FAILED,
                        "请求参数校验失败",
                        List.of(new FieldViolation("agent_id", "不能为空")));
            default -> new IllegalStateException("数据库口令错误：secret=abc");
        };
    }
}
