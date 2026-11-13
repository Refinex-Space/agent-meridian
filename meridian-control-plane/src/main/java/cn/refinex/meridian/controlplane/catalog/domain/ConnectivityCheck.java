package cn.refinex.meridian.controlplane.catalog.domain;

import java.time.Instant;
import java.util.Objects;

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
}
