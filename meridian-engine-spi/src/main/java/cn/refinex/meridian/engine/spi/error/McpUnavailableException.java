package cn.refinex.meridian.engine.spi.error;

/**
 * 装配智能体时连不上它挂载的 MCP 服务：初始化握手或工具发现失败（ADR-0035）。
 *
 * <p>MCP 服务的工具决定了模型能看到的工具集，缺一个服务装配出的实例会被缓存复用，所以不降级为"少一组工具"，而是让本轮以可重试的
 * 错误结束、重新排队，下次认领时重新装配。
 */
public class McpUnavailableException extends RetryableTurnException {

    /**
     * 创建异常。
     *
     * @param detail 面向调用方的说明，只含服务名；不含端点、请求头与凭据
     * @param cause 底层错误，只进日志
     */
    public McpUnavailableException(String detail, Throwable cause) {
        super(ToolErrorCode.MCP_UNAVAILABLE, detail, cause);
    }
}
