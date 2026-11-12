package cn.refinex.meridian.testsupport.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

/**
 * 进程内的 MCP 服务（ADR-0047）：嵌入式 Tomcat 在回环地址的随机端口上，以 Servlet 注册 MCP SDK 0.17.2 的 Streamable HTTP（{@code /mcp}）
 * 与 SSE（{@code /sse}，消息端点 {@code /sse/message}）两种传输。每个请求校验 {@code Authorization: Bearer {token}}，不对时 401，并记录
 * 收到的方法、路径与 {@code Authorization} 头（{@link #requests()}）。
 *
 * <p>工具：{@code lookup_order}（返回订单状态）、{@code create_ticket}（记录调用并返回工单号）。
 *
 * <p>线程安全：记录用并发集合；用完 {@link #close()}。
 */
public final class TestMcpServer implements AutoCloseable {

    /** 工具 {@code lookup_order} 的返回文本前缀。 */
    public static final String LOOKUP_RESULT = "订单已发货：";

    private final Tomcat tomcat;
    private final String token;
    private final McpSyncServer streamable;
    private final McpSyncServer sse;
    private final Path baseDir;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> ticketCalls = new CopyOnWriteArrayList<>();

    private TestMcpServer(String token) throws IOException, LifecycleException {
        this.token = token;
        this.baseDir = Files.createTempDirectory("meridian-test-mcp");
        HttpServletStreamableServerTransportProvider streamableTransport =
                HttpServletStreamableServerTransportProvider.builder()
                        .mcpEndpoint("/mcp")
                        .build();
        HttpServletSseServerTransportProvider sseTransport = HttpServletSseServerTransportProvider.builder()
                .sseEndpoint("/sse")
                .messageEndpoint("/sse/message")
                .build();
        this.streamable = server(McpServer.sync(streamableTransport), "crm");
        this.sse = server(McpServer.sync(sseTransport), "crm-sse");
        this.tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setHostname("127.0.0.1");
        tomcat.getConnector().setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        Context context = tomcat.addContext("", baseDir.toString());
        Tomcat.addServlet(context, "mcp", guarded(streamableTransport)).setAsyncSupported(true);
        context.addServletMappingDecoded("/mcp", "mcp");
        Tomcat.addServlet(context, "sse", guarded(sseTransport)).setAsyncSupported(true);
        context.addServletMappingDecoded("/sse", "sse");
        context.addServletMappingDecoded("/sse/message", "sse");
        tomcat.start();
    }

    /**
     * 在随机端口启动。
     *
     * @param token 服务端接受的 Bearer 令牌
     * @return 服务
     */
    public static TestMcpServer start(String token) {
        try {
            return new TestMcpServer(token);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (LifecycleException e) {
            throw new IllegalStateException("启动进程内 MCP 服务失败", e);
        }
    }

    /**
     * Streamable HTTP 传输的端点。
     *
     * @return {@code http://127.0.0.1:{port}/mcp}
     */
    public String streamableUrl() {
        return "http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/mcp";
    }

    /**
     * SSE 传输的端点。
     *
     * @return {@code http://127.0.0.1:{port}/sse}
     */
    public String sseUrl() {
        return "http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/sse";
    }

    /**
     * 收到的请求，按到达顺序。
     *
     * @return 请求记录
     */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /**
     * {@code create_ticket} 收到的入参，按调用顺序。
     *
     * @return 入参
     */
    public List<Map<String, Object>> ticketCalls() {
        return List.copyOf(ticketCalls);
    }

    @Override
    public void close() {
        streamable.close();
        sse.close();
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (LifecycleException e) {
            throw new IllegalStateException("停止进程内 MCP 服务失败", e);
        }
    }

    private HttpServlet guarded(HttpServlet transport) {
        return new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                String authorization = request.getHeader("Authorization");
                requests.add(new Request(request.getMethod(), request.getRequestURI(), authorization));
                if (!("Bearer " + token).equals(authorization)) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                try {
                    transport.service(request, response);
                } catch (jakarta.servlet.ServletException e) {
                    throw new IOException(e);
                }
            }
        };
    }

    private McpSyncServer server(McpServer.SyncSpecification<?> spec, String name) {
        McpJsonMapper json = McpJsonMapper.getDefault();
        McpSchema.Tool lookup = McpSchema.Tool.builder()
                .name("lookup_order")
                .description("Look up an order by its ID.")
                .inputSchema(json, """
                        {"type":"object","properties":{"order_id":{"type":"string"}},"required":["order_id"]}""")
                .build();
        McpSchema.Tool ticket = McpSchema.Tool.builder()
                .name("create_ticket")
                .description("Create a support ticket.")
                .inputSchema(json, """
                        {"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}""")
                .build();
        return spec.serverInfo(name, "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(List.of(
                        McpServerFeatures.SyncToolSpecification.builder()
                                .tool(lookup)
                                .callHandler((exchange, request) ->
                                        text(LOOKUP_RESULT + request.arguments().get("order_id")))
                                .build(),
                        McpServerFeatures.SyncToolSpecification.builder()
                                .tool(ticket)
                                .callHandler((exchange, request) -> {
                                    ticketCalls.add(request.arguments());
                                    return text("工单已创建：" + request.arguments().get("title"));
                                })
                                .build()))
                .build();
    }

    private static McpSchema.CallToolResult text(String text) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false, null, null);
    }

    /**
     * 一个收到的请求。
     *
     * @param method 方法
     * @param path 路径
     * @param authorization {@code Authorization} 头；可为空
     */
    public record Request(String method, String path, String authorization) {}
}
