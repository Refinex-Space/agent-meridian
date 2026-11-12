package cn.refinex.meridian.controlplane.mcp.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** MCP 服务的错误码（ADR-0047）。 */
public enum McpErrorCode implements ErrorCode {
    /** MCP 服务不存在。 */
    NOT_FOUND("mcp_server.not_found", 404, "MCP 服务不存在"),
    /** 项目内已有同名的 MCP 服务。 */
    NAME_TAKEN("mcp_server.name_taken", 409, "名称已被使用"),
    /** MCP 服务仍被已发布的智能体版本引用；problem+json 带 {@code references}。 */
    IN_USE("mcp_server.in_use", 409, "MCP 服务仍被引用"),
    /** 连接 MCP 服务、握手或列出工具失败。 */
    PROBE_FAILED("mcp_server.probe_failed", 400, "MCP 服务探测失败");

    private final String code;
    private final int status;
    private final String title;

    McpErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
