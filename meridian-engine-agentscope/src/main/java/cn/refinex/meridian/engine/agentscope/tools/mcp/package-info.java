/**
 * MCP 客户端（ADR-0035）：装配时建立异步客户端并发现工具，每个远端工具包成 {@code mcp__{server}__{tool}} 的平台工具；
 * 凭据每次调用前解析，只放进本次调用的 Reactor Context，结果中的明文替换为 {@code ***}。
 *
 * <p>不负责 MCP 服务的登记与端点校验（control-plane）。
 */
package cn.refinex.meridian.engine.agentscope.tools.mcp;
