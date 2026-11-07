package cn.refinex.meridian.engine.spi.spec;

/**
 * 一条权限规则命中后的结果，见架构设计「权限判定」。
 *
 * <p>判定顺序沿用 AgentScope {@code PermissionEngine}：deny → ask → 工具自检 → allow → BYPASS → 默认 ask。
 */
public enum PermissionBehavior {
    /** 放行。 */
    ALLOW("allow"),
    /** 拒绝，工具结果为 DENIED。 */
    DENY("deny"),
    /** 需要确认，产生 {@code tool_confirmation} 待处理动作。 */
    ASK("ask");

    private final String value;

    PermissionBehavior(String value) {
        this.value = value;
    }

    /**
     * 返回配置与 API 中使用的取值。
     *
     * @return 小写取值
     */
    public String value() {
        return value;
    }
}
