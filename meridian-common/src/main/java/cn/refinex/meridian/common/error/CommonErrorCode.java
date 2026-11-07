package cn.refinex.meridian.common.error;

/**
 * 跨领域的通用错误码：请求格式与校验、资源、权限、幂等、租约与内部错误。
 *
 * <p>业务领域优先定义自己的错误码（如 {@code session.not_found}）；这里的码值用于框架异常的映射，
 * 以及确实不属于任何领域的情况。
 */
public enum CommonErrorCode implements ErrorCode {
    /** 请求体无法解析，或参数类型、格式不对。 */
    REQUEST_INVALID("request.invalid", 400, "请求格式错误"),
    /** 请求参数没有通过校验，具体字段见 problem+json 的 {@code errors}。 */
    VALIDATION_FAILED("request.validation_failed", 400, "请求参数校验失败"),
    /** ID 的前缀或 ULID 不合法。 */
    INVALID_ID("request.invalid_id", 400, "ID 格式错误"),
    /** 分页游标被篡改、截断或来自不兼容的版本。 */
    INVALID_CURSOR("request.invalid_cursor", 400, "分页游标无效"),
    /** 请求的 HTTP 方法不被该资源支持。 */
    METHOD_NOT_ALLOWED("request.method_not_allowed", 405, "不支持的请求方法"),
    /** 无法按 {@code Accept} 头给出响应。 */
    NOT_ACCEPTABLE("request.not_acceptable", 406, "无法提供所需的响应格式"),
    /** 请求体超过大小上限。 */
    PAYLOAD_TOO_LARGE("request.payload_too_large", 413, "请求体过大"),
    /** 请求的 {@code Content-Type} 不被支持。 */
    UNSUPPORTED_MEDIA_TYPE("request.unsupported_media_type", 415, "不支持的请求格式"),
    /** 请求被拒绝，但不属于以上任何一类（保留框架给出的原始状态码）。 */
    REQUEST_REJECTED("request.rejected", 400, "请求被拒绝"),
    /** 资源不存在，或调用方无权得知它存在。 */
    NOT_FOUND("resource.not_found", 404, "资源不存在"),
    /** 资源状态与请求冲突。 */
    CONFLICT("resource.conflict", 409, "资源状态冲突"),
    /** 调用方已认证，但没有执行该操作的权限。 */
    FORBIDDEN("access.forbidden", 403, "权限不足"),
    /** 同一个 {@code Idempotency-Key} 在 24 小时内被用于不同的请求体。 */
    IDEMPOTENCY_KEY_REUSED("idempotency.key_reused", 409, "幂等键已用于不同的请求"),
    /** 同一个 {@code Idempotency-Key} 的首次请求仍在处理中。 */
    IDEMPOTENCY_IN_PROGRESS("idempotency.in_progress", 409, "相同幂等键的请求正在处理"),
    /** 当前节点已失去该线程的租约，本次执行的任何写入都被拒绝，见架构设计「租约与 fencing」。 */
    LEASE_LOST("turn.lease_lost", 409, "租约已失效"),
    /** 依赖的服务暂时不可用，可以稍后重试。 */
    SERVICE_UNAVAILABLE("service.unavailable", 503, "服务暂时不可用"),
    /** 未预期的内部错误；detail 不暴露内部信息，排查以 {@code request_id} 关联日志。 */
    INTERNAL("internal.error", 500, "内部错误");

    private final String code;
    private final int status;
    private final String title;

    CommonErrorCode(String code, int status, String title) {
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
