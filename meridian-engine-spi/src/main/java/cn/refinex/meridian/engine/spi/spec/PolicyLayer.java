package cn.refinex.meridian.engine.spi.spec;

/**
 * 权限规则所属的层，按优先级从高到低排列，见架构设计「权限判定」中合成的五层规则。
 *
 * <p>合成后的规则仍保留来源层，用于审计和"记住此决定"写回时定位作用域。
 */
public enum PolicyLayer {
    /** 平台安全规则：最高优先级的 deny，例如写受保护路径、读凭据文件。 */
    PLATFORM("platform"),
    /** 租户策略。 */
    TENANT("tenant"),
    /** 项目策略，含"记住此决定"写入的项目级规则。 */
    PROJECT("project"),
    /** 智能体版本的策略。 */
    AGENT_VERSION("agent_version"),
    /** 会话级规则，含"记住此决定"写入的会话级规则。 */
    SESSION("session");

    private final String value;

    PolicyLayer(String value) {
        this.value = value;
    }

    /**
     * 返回规则来源的取值，翻译为 AgentScope 规则的 {@code source}。
     *
     * @return 小写下划线形式的取值
     */
    public String value() {
        return value;
    }
}
