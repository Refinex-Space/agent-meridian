package cn.refinex.meridian.security.web;

import cn.refinex.meridian.common.id.Ids;
import java.util.regex.Pattern;

/**
 * 请求 ID 的约定：响应头 {@code X-Request-Id}（架构设计「API 设计」中的「约定」），日志 MDC 键 {@code request_id}，
 * problem+json 扩展字段 {@code request_id}。
 *
 * <p>调用方传入的请求 ID 合法时沿用，便于跨系统串联日志；不合法或缺失时生成新的 ULID。
 */
public final class RequestIds {

    /** 请求与响应头名。 */
    public static final String HEADER = "X-Request-Id";

    /** 日志 MDC 键，与 problem+json 的扩展字段同名。 */
    public static final String MDC_KEY = "request_id";

    /** 请求属性（Servlet）与交换属性（WebFlux）的键，错误映射从这里读取当前请求的 ID。 */
    public static final String ATTRIBUTE = RequestIds.class.getName() + ".requestId";

    /** 传入的请求 ID 的允许格式：不超过 128 个字符的字母、数字与 {@code ._:-}，防止日志注入与超长头。 */
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private RequestIds() {}

    /**
     * 沿用合法的传入请求 ID，否则生成新的。
     *
     * @param incoming 请求头中的值，可为空
     * @return 本次请求的 ID
     */
    public static String resolve(String incoming) {
        return incoming != null && ACCEPTED.matcher(incoming).matches() ? incoming : Ids.newUlid();
    }
}
