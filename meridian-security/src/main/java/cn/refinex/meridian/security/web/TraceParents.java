package cn.refinex.meridian.security.web;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W3C Trace Context 的 {@code traceparent} 请求头（架构设计「API 设计」中"支持传入 traceparent"）。
 *
 * <p>合法的 {@code traceparent} 在响应中原样返回，其中的 trace-id 写入日志 MDC 键 {@code trace_id}，把平台日志关联到调用方的追踪；
 * 不合法的值被忽略，不拒绝请求。跨服务的追踪传播与 span 由 T27 接入 OpenTelemetry 后完成。
 */
public final class TraceParents {

    /** 请求与响应头名。 */
    public static final String HEADER = "traceparent";

    /** 日志 MDC 键与 Reactor Context 键（编码规范「日志」）。 */
    public static final String MDC_KEY = "trace_id";

    /** {@code version-traceid-parentid-flags}，全小写十六进制；版本 ff 无效。 */
    private static final Pattern FORMAT =
            Pattern.compile("(?!ff)([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");

    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_PARENT_ID = "0".repeat(16);

    private TraceParents() {}

    /**
     * 解析 {@code traceparent}。
     *
     * @param header 请求头的值，可为空
     * @return 合法时为其中的 trace-id；缺失或不合法时为空
     */
    public static Optional<String> traceId(String header) {
        if (header == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(header);
        if (!matcher.matches()
                || matcher.group(2).equals(ZERO_TRACE_ID)
                || matcher.group(3).equals(ZERO_PARENT_ID)) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(2));
    }
}
