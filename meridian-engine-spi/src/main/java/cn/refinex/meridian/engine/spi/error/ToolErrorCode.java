package cn.refinex.meridian.engine.spi.error;

import cn.refinex.meridian.common.error.ErrorCode;

/**
 * 工具层面决定一轮如何结束的错误码。单次工具调用的失败不在这里：它作为出错的工具结果交给模型，不结束本轮（架构设计「平台工具」）。
 */
public enum ToolErrorCode implements ErrorCode {
    /** 装配时连不上智能体挂载的 MCP 服务（初始化或工具发现失败），可重试：本轮以 {@code session.error(retryable)} 结束并重新排队。 */
    MCP_UNAVAILABLE("tool.mcp_unavailable", 503, "MCP 服务暂时不可用");

    private final String code;
    private final int status;
    private final String title;

    ToolErrorCode(String code, int status, String title) {
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
