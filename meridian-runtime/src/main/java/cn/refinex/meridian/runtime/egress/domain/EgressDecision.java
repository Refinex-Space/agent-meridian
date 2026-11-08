package cn.refinex.meridian.runtime.egress.domain;

import java.util.Objects;

/**
 * 一次出网授权的结论。
 *
 * @param reason 结论与原因；必填
 * @param sessionId 源 IP 所属的会话；找不到会话时为 null
 * @param target 解析出的目标；目标不合法时为 null
 */
public record EgressDecision(Reason reason, String sessionId, EgressTarget target) {

    /**
     * 创建结论。
     *
     * @throws NullPointerException 缺少原因时
     */
    public EgressDecision {
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * 是否放行。
     *
     * @return 只有 {@link Reason#ALLOWED} 为 true
     */
    public boolean allowed() {
        return reason == Reason.ALLOWED;
    }

    /** 结论的原因，取值也用于指标标签与接口响应。 */
    public enum Reason {
        /** 目标在会话环境的白名单内，放行。 */
        ALLOWED("allowed"),
        /** 目标不在会话环境的白名单内。 */
        NOT_ALLOWLISTED("not_allowlisted"),
        /** 源 IP 不属于任何持有沙箱的会话（沙箱已删除、预热池中尚未认领，或多个会话记录了同一 IP）。 */
        UNKNOWN_SOURCE("unknown_source"),
        /** 目标不是合法的主机与端口。 */
        INVALID_TARGET("invalid_target");

        private final String value;

        Reason(String value) {
            this.value = value;
        }

        /**
         * 返回指标标签与接口中使用的取值。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }
    }
}
