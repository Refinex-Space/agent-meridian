package cn.refinex.meridian.runtime.egress.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 出网授权拒绝的错误码（内部接口 {@code /internal/v1/egress:authorize} 的 403），与 {@link EgressDecision.Reason} 一一对应。 */
public enum EgressErrorCode implements ErrorCode {
    /** 目标不在会话环境的白名单内。 */
    NOT_ALLOWLISTED("egress.not_allowlisted", "目标不在出网白名单内"),
    /** 源 IP 不属于任何持有沙箱的会话。 */
    UNKNOWN_SOURCE("egress.unknown_source", "源 IP 不属于任何会话沙箱"),
    /** 目标不是合法的主机与端口。 */
    INVALID_TARGET("egress.invalid_target", "出网目标不合法");

    /** 拒绝出网一律是 403。 */
    private static final int FORBIDDEN = 403;

    private final String code;
    private final String title;

    EgressErrorCode(String code, String title) {
        this.code = code;
        this.title = title;
    }

    /**
     * 拒绝原因对应的错误码。
     *
     * @param reason 拒绝的原因；不能是 {@link EgressDecision.Reason#ALLOWED}
     * @return 错误码
     * @throws IllegalArgumentException 原因是放行时
     */
    public static EgressErrorCode of(EgressDecision.Reason reason) {
        return switch (reason) {
            case NOT_ALLOWLISTED -> NOT_ALLOWLISTED;
            case UNKNOWN_SOURCE -> UNKNOWN_SOURCE;
            case INVALID_TARGET -> INVALID_TARGET;
            case ALLOWED -> throw new IllegalArgumentException("放行没有错误码");
        };
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return FORBIDDEN;
    }

    @Override
    public String title() {
        return title;
    }
}
