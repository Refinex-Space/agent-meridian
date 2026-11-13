package cn.refinex.meridian.controlplane.catalog.domain;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * 一次连通性检查的结果（ADR-0053）：供应商账号的连通性测试与 MCP 服务的探测共用，保存在各自的 {@code last_check} 中。
 *
 * @param status {@code ok}、{@code auth_failed}、{@code unreachable}、{@code error}
 * @param httpStatus 对方返回的 HTTP 状态码；没有拿到响应时为空
 * @param latencyMs 从发起到得到结果的毫秒数
 * @param message 失败的说明（不含凭据与响应内容）；成功时为空
 * @param checkedAt 检查的时间
 */
public record ConnectivityCheck(String status, Integer httpStatus, long latencyMs, String message, Instant checkedAt) {

    /** 连通且凭据有效。 */
    public static final String OK = "ok";

    /** 对方返回 401 或 403。 */
    public static final String AUTH_FAILED = "auth_failed";

    /** 连接失败或超时。 */
    public static final String UNREACHABLE = "unreachable";

    /** 其他失败。 */
    public static final String ERROR = "error";

    /** 说明的长度上限：异常信息可能很长。 */
    static final int MESSAGE_LIMIT = 500;

    /**
     * 创建结果。
     *
     * @throws NullPointerException 缺少状态或时间时
     */
    public ConnectivityCheck {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (message != null && message.length() > MESSAGE_LIMIT) {
            message = message.substring(0, MESSAGE_LIMIT);
        }
    }

    /**
     * 按 HTTP 状态码给出结果：2xx 为 {@code ok}，401、403 为 {@code auth_failed}，其余为 {@code error}。
     *
     * @param httpStatus 状态码
     * @param latencyMs 耗时
     * @param checkedAt 检查的时间
     * @return 结果
     */
    public static ConnectivityCheck ofHttpStatus(int httpStatus, long latencyMs, Instant checkedAt) {
        if (httpStatus >= 200 && httpStatus < 300) {
            return new ConnectivityCheck(OK, httpStatus, latencyMs, null, checkedAt);
        }
        String status = httpStatus == 401 || httpStatus == 403 ? AUTH_FAILED : ERROR;
        return new ConnectivityCheck(status, httpStatus, latencyMs, "对方返回 HTTP " + httpStatus, checkedAt);
    }

    /**
     * 是否连通。
     *
     * @return {@code ok} 时为 true
     */
    public boolean ok() {
        return OK.equals(status);
    }

    /**
     * 连接失败的分类说明，供 {@code last_check.message} 与探测失败的 {@code detail}：只给分类与 HTTP 状态码，不给异常消息原文。原文
     * 可能带内网地址、端口与对端返回的内容，管理员能借此探测内网（R2 审查 K-04、I-06）；原因链留在服务端日志。
     *
     * @param error 连接、握手或请求中的异常
     * @return 分类说明
     */
    public static String failureReason(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof TimeoutException) {
                return "连接或响应超时";
            }
            if (t instanceof UnknownHostException) {
                return "域名解析失败";
            }
            if (t instanceof ConnectException) {
                return "连接被拒绝";
            }
            if (t instanceof SSLException) {
                return "TLS 握手失败";
            }
            String message = t.getMessage();
            if (message != null) {
                Matcher status = HTTP_STATUS.matcher(message);
                if (status.find()) {
                    return "对端返回 HTTP " + status.group(1);
                }
            }
        }
        return "连接失败（" + root.getClass().getSimpleName() + "）";
    }

    /** 异常消息中的 HTTP 状态码：SDK 与 JDK 客户端的写法是 "status 401"、"status code: 401"、"HTTP 401"。 */
    private static final Pattern HTTP_STATUS = Pattern.compile("(?i)(?:status(?: code)?:?|HTTP)\\s*([1-5]\\d\\d)\\b");
}
