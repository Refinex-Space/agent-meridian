package cn.refinex.meridian.security.web;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.error.ReferencedException;
import cn.refinex.meridian.common.error.ValidationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * 把异常映射为 problem+json（RFC 9457），带扩展字段 {@code code} 与 {@code request_id}
 * （架构设计「API 设计」中的「约定」）。MVC 与 WebFlux 的适配器只负责传输，映射逻辑只有这一份。
 *
 * <p>映射规则：
 * <ul>
 *   <li>{@link MeridianException}：取它的错误码与 detail；{@link ValidationException} 另输出 {@code errors}，
 *       {@link ReferencedException} 另输出 {@code references}（{@code [{type, id, name}]}）。
 *   <li>参数绑定校验失败（{@link BindingResult}）：{@code request.validation_failed}，字段名转为 snake_case。
 *   <li>请求参数、请求头的约束校验失败（{@link HandlerMethodValidationException}）：{@code request.validation_failed}，
 *       字段名取参数在 HTTP 中的名字（例如 {@code limit}、{@code Idempotency-Key}）。
 *   <li>请求体无法解析、参数类型不对：{@code request.invalid}。
 *   <li>Spring 的其他 Web 异常（{@link ErrorResponse}）：保留其状态码，按状态码选通用错误码，detail 取错误码标题。
 *   <li>其他异常：500 {@code internal.error}，detail 不暴露内部信息。
 * </ul>
 *
 * <p>日志在这里记录且只记录一次（编码规范「错误处理」）：5xx 记 ERROR 带堆栈，4xx 记 DEBUG。
 * 线程安全：无状态，可共享。
 */
public final class ProblemMapper {

    private static final Logger log = LoggerFactory.getLogger(ProblemMapper.class);

    /** 500 时返回给调用方的 detail：不含任何内部信息，排查以 request_id 关联日志。 */
    static final String INTERNAL_DETAIL = "服务内部错误，请携带 request_id 联系管理员";

    /**
     * 映射一个异常。
     *
     * @param error 处理请求时抛出的异常
     * @param requestId 本次请求的 ID
     * @param instance 请求路径，作为 problem+json 的 {@code instance}
     * @return problem+json 响应
     */
    public Problem map(Throwable error, String requestId, String instance) {
        Mapped mapped = classify(error);
        if (mapped.status >= 500) {
            log.error("请求处理失败 request_id={} instance={}", requestId, instance, error);
        } else {
            log.debug("请求被拒绝 request_id={} instance={} code={}", requestId, instance, mapped.code.code(), error);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", mapped.code.type().toString());
        body.put("title", mapped.code.title());
        body.put("status", mapped.status);
        body.put("detail", mapped.detail);
        body.put("instance", instance);
        body.put("code", mapped.code.code());
        body.put("request_id", requestId);
        if (!mapped.violations.isEmpty()) {
            body.put(
                    "errors",
                    mapped.violations.stream()
                            .map(v -> {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("field", v.field());
                                item.put("message", v.message());
                                return item;
                            })
                            .toList());
        }
        if (error instanceof ReferencedException referenced) {
            body.put(
                    "references",
                    referenced.references().stream()
                            .map(r -> {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("type", r.type());
                                item.put("id", r.id());
                                item.put("name", r.name());
                                return item;
                            })
                            .toList());
        }
        return new Problem(mapped.status, body);
    }

    private static Mapped classify(Throwable error) {
        if (error instanceof ValidationException e) {
            return new Mapped(e.errorCode(), e.errorCode().status(), e.detail(), e.violations());
        }
        if (error instanceof MeridianException e) {
            return new Mapped(e.errorCode(), e.errorCode().status(), e.detail(), List.of());
        }
        if (error instanceof BindingResult bindingResult) {
            List<FieldViolation> violations = bindingResult.getFieldErrors().stream()
                    .map(f -> new FieldViolation(toSnakeCase(f.getField()), f.getDefaultMessage()))
                    .toList();
            return new Mapped(CommonErrorCode.VALIDATION_FAILED, 400, "请求参数校验失败", violations);
        }
        if (error instanceof HandlerMethodValidationException e) {
            List<FieldViolation> violations = e.getParameterValidationResults().stream()
                    .flatMap(result -> result.getResolvableErrors().stream()
                            .map(err ->
                                    new FieldViolation(httpName(result.getMethodParameter()), err.getDefaultMessage())))
                    .toList();
            return new Mapped(CommonErrorCode.VALIDATION_FAILED, 400, "请求参数校验失败", violations);
        }
        if (error instanceof HttpMessageNotReadableException) {
            return new Mapped(CommonErrorCode.REQUEST_INVALID, 400, "请求体无法解析", List.of());
        }
        if (error instanceof TypeMismatchException e) {
            String name = e.getPropertyName() == null ? "参数" : "参数 " + toSnakeCase(e.getPropertyName());
            return new Mapped(CommonErrorCode.REQUEST_INVALID, 400, name + " 的类型不正确", List.of());
        }
        if (error instanceof ErrorResponse e) {
            int status = e.getStatusCode().value();
            ErrorCode code = codeForStatus(status);
            // 框架给出的 detail 因 Web 栈而异且为英文，统一用错误码的标题，两种 Web 栈的输出才一致
            String detail = status >= 500 ? INTERNAL_DETAIL : code.title();
            return new Mapped(code, status, detail, List.of());
        }
        return new Mapped(CommonErrorCode.INTERNAL, 500, INTERNAL_DETAIL, List.of());
    }

    private static ErrorCode codeForStatus(int status) {
        return switch (status) {
            case 400 -> CommonErrorCode.REQUEST_INVALID;
            case 403 -> CommonErrorCode.FORBIDDEN;
            case 404 -> CommonErrorCode.NOT_FOUND;
            case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> CommonErrorCode.NOT_ACCEPTABLE;
            case 409 -> CommonErrorCode.CONFLICT;
            case 413 -> CommonErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 503 -> CommonErrorCode.SERVICE_UNAVAILABLE;
            default -> status >= 500 ? CommonErrorCode.INTERNAL : CommonErrorCode.REQUEST_REJECTED;
        };
    }

    /** 参数在 HTTP 中的名字：查询参数、请求头、路径变量取注解中的名字，其余取 Java 参数名的 snake_case 形式。 */
    private static String httpName(MethodParameter parameter) {
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        if (param != null && !param.value().isEmpty()) {
            return param.value();
        }
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.value().isEmpty()) {
            return header.value();
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null && !path.value().isEmpty()) {
            return path.value();
        }
        String name = parameter.getParameterName();
        return name == null ? "参数" : toSnakeCase(name);
    }

    /** Java 属性名转为 API 的 snake_case 字段名，例如 {@code metadata.inputTokens} → {@code metadata.input_tokens}。 */
    static String toSnakeCase(String name) {
        StringBuilder result = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && name.charAt(i - 1) != '.') {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private record Mapped(ErrorCode code, int status, String detail, List<FieldViolation> violations) {}
}
