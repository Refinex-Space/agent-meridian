package cn.refinex.meridian.engine.agentscope.tools.mcp;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import java.net.URI;
import java.net.http.HttpRequest;

/**
 * MCP 客户端的请求定制器：每次 HTTP 请求从传输上下文取出 {@link McpCredentials}，把凭据写进请求头（架构设计「凭据」：凭据只在
 * 工具执行边界注入，ADR-0035）。
 *
 * <p>2.0.4 的 {@code McpClientBuilder.httpRequestCustomizer} 只接受同步定制器，MCP SDK 把它包成 {@code Mono.fromSupplier}，在发请求的
 * 线程上执行，所以这里只做内存操作、不解析凭据：凭据由调用方在订阅之前解析好放进 Reactor Context。传输上下文里没有凭据时（例如
 * 服务端主动推送的连接）不加任何头。
 *
 * <p>线程安全：无状态。
 */
final class McpCredentialInjector implements McpSyncHttpClientRequestCustomizer {

    @Override
    public void customize(
            HttpRequest.Builder builder, String method, URI endpoint, String body, McpTransportContext context) {
        if (context != null && context.get(McpCredentials.CONTEXT_KEY) instanceof McpCredentials credentials) {
            credentials.headers().forEach((name, value) -> builder.setHeader(name, value.reveal()));
        }
    }
}
