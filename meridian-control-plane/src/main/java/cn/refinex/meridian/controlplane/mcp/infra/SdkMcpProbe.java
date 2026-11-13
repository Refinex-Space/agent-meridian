package cn.refinex.meridian.controlplane.mcp.infra;

import cn.refinex.meridian.controlplane.catalog.domain.ConnectivityCheck;
import cn.refinex.meridian.controlplane.mcp.app.McpProbe;
import cn.refinex.meridian.controlplane.mcp.domain.McpTool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 用 MCP Java SDK（与 AgentScope 2.0.4 同版本 0.17.2）探测 MCP 服务（ADR-0047）：同步客户端，初始化握手后逐页列出工具，用完即关。
 *
 * <p>与 runtime 的 {@code McpConnector} 一致：Streamable HTTP 与 SSE 两种传输，端点拆成基础地址与路径；HTTP 客户端不跟随重定向（JDK
 * 默认），不走系统代理；请求头只放进本次探测的请求，异常消息不含请求头。
 *
 * <p>线程安全：无状态，每次探测新建客户端。
 */
@Component
public class SdkMcpProbe implements McpProbe {

    private static final Logger log = LoggerFactory.getLogger(SdkMcpProbe.class);

    /** 建立连接的超时。 */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 单次请求与初始化握手的超时：探测在管理请求中同步执行，不能太长。 */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** 最多翻多少页工具：防止服务端不断返回游标。 */
    static final int MAX_PAGES = 50;

    @Override
    public List<McpTool> probe(String transport, String url, Map<String, String> headers) {
        McpSyncClient client = McpClient.sync(transport(transport, url, headers))
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(REQUEST_TIMEOUT)
                .clientInfo(new McpSchema.Implementation("meridian-control-plane", "1.0.0"))
                .build();
        try {
            client.initialize();
            List<McpTool> tools = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
                if (page.tools() != null) {
                    page.tools().forEach(tool -> tools.add(new McpTool(tool.name(), tool.description())));
                }
                cursor = page.nextCursor();
            } while (cursor != null && ++pages < MAX_PAGES);
            tools.sort(Comparator.comparing(McpTool::name));
            return List.copyOf(tools);
        } catch (RuntimeException e) {
            log.info("MCP 服务探测失败 transport={} error={}", transport, rootMessage(e));
            // 对外只给分类说明，不回显对端返回的内容与内网地址（R2 审查 K-04）；原因留在日志
            throw new McpProbeException("连接 MCP 服务、握手或列出工具失败：" + ConnectivityCheck.failureReason(e), e);
        } finally {
            client.closeGracefully();
        }
    }

    private static McpClientTransport transport(String transport, String url, Map<String, String> headers) {
        URI uri = URI.create(url);
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        Consumer<HttpClient.Builder> client = builder -> builder.connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY);
        Consumer<HttpRequest.Builder> request = builder -> headers.forEach(builder::setHeader);
        return switch (transport) {
            case "streamable_http" ->
                HttpClientStreamableHttpTransport.builder(base)
                        .endpoint(path)
                        .customizeClient(client)
                        .customizeRequest(request)
                        .connectTimeout(CONNECT_TIMEOUT)
                        .build();
            case "sse" ->
                HttpClientSseClientTransport.builder(base)
                        .sseEndpoint(path)
                        .customizeClient(client)
                        .customizeRequest(request)
                        .connectTimeout(CONNECT_TIMEOUT)
                        .build();
            default -> throw new IllegalArgumentException("未知的 MCP 传输：" + transport);
        };
    }

    /** 最内层原因的类型与消息：SDK 的外层异常多是"初始化失败"，原因才说明是连不上还是 401。 */
    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? "" : root.getMessage();
        if (message.length() > 300) {
            message = message.substring(0, 300) + "…";
        }
        return root.getClass().getSimpleName() + (message.isEmpty() ? "" : " " + message);
    }
}
