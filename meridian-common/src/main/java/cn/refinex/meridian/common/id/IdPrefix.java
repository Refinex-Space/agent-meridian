package cn.refinex.meridian.common.id;

/**
 * 对外 ID 的前缀，与架构设计「ID 规则」表一一对应。
 *
 * <p>新增对象类型时先修改架构设计的 ID 规则表，再在这里加常量；前缀一经发布不能修改。
 */
public enum IdPrefix {
    /** 租户。 */
    TENANT("ten"),
    /** 项目。 */
    PROJECT("prj"),
    /** API Key。 */
    API_KEY("key"),
    /** 智能体。 */
    AGENT("agt"),
    /** 智能体版本。 */
    AGENT_VERSION("agv"),
    /** 环境。 */
    ENVIRONMENT("env"),
    /** 模型。 */
    MODEL("mdl"),
    /** 供应商账号。 */
    PROVIDER_ACCOUNT("prv"),
    /** 凭据。 */
    SECRET("sec"),
    /** 技能。 */
    SKILL("skl"),
    /** 会话。 */
    SESSION("ses"),
    /** 线程。 */
    THREAD("thr"),
    /** 轮次。 */
    TURN("trn"),
    /** 会话事件。 */
    EVENT("evt"),
    /** 工具调用。 */
    TOOL_INVOCATION("tiv"),
    /** 待处理动作。 */
    PENDING_ACTION("act"),
    /** 知识库。 */
    KNOWLEDGE_BASE("kb"),
    /** 文档。 */
    DOCUMENT("doc"),
    /** 文件。 */
    FILE("fil"),
    /** MCP 服务。 */
    MCP_SERVER("mcp"),
    /** 租户成员（ADR-0042）。 */
    MEMBER("mbr"),
    /** 角色绑定（ADR-0042）。 */
    ROLE_BINDING("rbd");

    private final String value;

    IdPrefix(String value) {
        this.value = value;
    }

    /**
     * 返回前缀文本，例如 {@code ses}，不含下划线。
     *
     * @return 前缀文本
     */
    public String value() {
        return value;
    }
}
