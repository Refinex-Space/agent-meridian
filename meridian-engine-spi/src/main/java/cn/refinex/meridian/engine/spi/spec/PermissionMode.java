package cn.refinex.meridian.engine.spi.spec;

/**
 * 权限模式，取值与 AgentScope {@code PermissionMode} 一一对应，见架构设计「权限判定」。
 *
 * <p>模式取自智能体配置，会话可以收紧、不能放宽；无人值守会话与所有子线程固定为 {@link #DONT_ASK}。
 */
public enum PermissionMode {
    /** 默认：没有命中任何规则的工具调用需要确认（默认 ask）。 */
    DEFAULT("default"),
    /** 自动放行文件编辑类工具，其余同 {@link #DEFAULT}。 */
    ACCEPT_EDITS("accept_edits"),
    /** 只读探索：拒绝有写副作用的工具。 */
    EXPLORE("explore"),
    /** 跳过确认：没有命中 deny、ask 规则的调用一律放行。 */
    BYPASS("bypass"),
    /** 不询问：最后一步的默认 ask 改为 deny；显式 ask 规则仍返回 ask，需要平台在合成时改写。 */
    DONT_ASK("dont_ask");

    private final String value;

    PermissionMode(String value) {
        this.value = value;
    }

    /**
     * 返回配置与 API 中使用的取值，例如 {@code accept_edits}。
     *
     * @return 小写下划线形式的取值
     */
    public String value() {
        return value;
    }
}
