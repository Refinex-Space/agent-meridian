package cn.refinex.meridian.controlplane.mcp.app;

import cn.refinex.meridian.controlplane.mcp.domain.McpTool;
import java.util.List;
import java.util.Map;

/**
 * 探测一个 MCP 服务：连接、初始化握手、列出全部工具（ADR-0047）。实现不跟随重定向、带超时；请求头只用于这一次探测。
 */
public interface McpProbe {

    /**
     * 探测。
     *
     * @param transport 传输：{@code streamable_http} 或 {@code sse}
     * @param url 服务端点，已按端点策略校验
     * @param headers 请求头：头名 → 明文值
     * @return 工具，按名字排序
     * @throws McpProbeException 连接、握手或列出工具失败时；消息不含请求头
     */
    List<McpTool> probe(String transport, String url, Map<String, String> headers);

    /** 探测失败。 */
    final class McpProbeException extends RuntimeException {

        /**
         * 创建异常。
         *
         * @param message 不含凭据的说明
         * @param cause 原因
         */
        public McpProbeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
