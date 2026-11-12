package cn.refinex.meridian.controlplane.web;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.net.EndpointPolicy;
import java.util.List;

/**
 * 管理员配置的端点（供应商账号与模型的 {@code base_url}、联网搜索后端）在写入时的校验（ADR-0045 第 5 条，登记事项 D52）：不允许时以
 * 400 {@code request.validation_failed} 拒绝，{@code errors} 指向字段。
 */
public final class Endpoints {

    private Endpoints() {}

    /**
     * 校验一个端点；为空时不校验（字段可空，必填与否由调用方判断）。
     *
     * @param policy 端点策略
     * @param url 端点
     * @param field 请求中的字段名
     * @throws ValidationException 不允许时
     */
    public static void check(EndpointPolicy policy, String url, String field) {
        if (url == null) {
            return;
        }
        policy.violation(url).ifPresent(reason -> {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED, reason, List.of(new FieldViolation(field, reason)));
        });
    }
}
