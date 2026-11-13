package cn.refinex.meridian.engine.agentscope.tools.mcp;

import cn.refinex.meridian.engine.spi.spec.McpServerSpec;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个 MCP 服务在一个智能体实例中的客户端，以及服务重启后的恢复（登记事项 D53 A-F8）。
 *
 * <p>MCP Java SDK 0.17.2 的行为（T19 用 {@code McpToolsIT} 在原端口重启服务实测）：服务端的会话随重启丢失，下一次请求得到 404，
 * 这次调用以"会话已终止"失败；SDK 随即在后台重新初始化，但后台没有调用方的 Reactor Context，平台放在其中的凭据到不了请求头，
 * 初始化被服务端拒绝，之后的调用都加入这次失败的初始化，客户端在实例被淘汰之前一直不可用。
 *
 * <p>所以由平台恢复：调用因会话不存在、会话已终止或客户端初始化失败而失败时（这三种情况下服务端都没有执行这次工具调用），用本次调用的
 * 凭据新建并初始化一个客户端、换下旧的，再重试一次；重试仍失败就把错误交给模型。并发的调用只重建一次。工具清单仍是装配时发现的，
 * 服务端增删工具要等实例重建（新版本或空闲淘汰）。
 *
 * <p>线程安全：当前客户端的替换在本对象的锁内。关闭时关闭当前客户端，它是智能体实例的资源（{@code ToolBundle.resources}）。
 */
final class McpSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpSession.class);

    private final McpServerSpec server;
    private final Function<McpServerSpec, McpClientWrapper> factory;
    private final Duration initializationTimeout;
    private volatile McpClientWrapper client;
    private volatile boolean closed;

    /**
     * 创建会话。
     *
     * @param server 服务配置
     * @param client 装配时已初始化的客户端
     * @param factory 新建（未初始化的）客户端
     * @param initializationTimeout 重建时等待初始化的上限
     */
    McpSession(
            McpServerSpec server,
            McpClientWrapper client,
            Function<McpServerSpec, McpClientWrapper> factory,
            Duration initializationTimeout) {
        this.server = Objects.requireNonNull(server, "server");
        this.client = Objects.requireNonNull(client, "client");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.initializationTimeout = Objects.requireNonNull(initializationTimeout, "initializationTimeout");
    }

    /**
     * 调用一个工具；会话丢失时重建客户端并重试一次。
     *
     * @param tool 服务端的工具名
     * @param input 入参
     * @param meta 请求的 {@code _meta}；可为空
     * @param credentials 本次调用的凭据
     * @param timeout 等待结果的上限
     * @return 结果
     * @throws RuntimeException 调用失败时（重试后仍失败，或失败与会话无关）
     */
    McpSchema.CallToolResult callTool(
            String tool,
            Map<String, Object> input,
            Map<String, Object> meta,
            McpCredentials credentials,
            Duration timeout) {
        McpClientWrapper current = client;
        try {
            return current.callTool(tool, input, meta)
                    .contextWrite(credentials.write())
                    .block(timeout);
        } catch (RuntimeException e) {
            if (closed || !sessionLost(e)) {
                throw e;
            }
            log.warn(
                    "MCP 服务的会话已失效，重建客户端后重试一次 mcpServerId={} tool={} cause={}",
                    server.mcpServerId(),
                    tool,
                    e.toString());
            McpClientWrapper fresh = reconnect(current, credentials);
            return fresh.callTool(tool, input, meta)
                    .contextWrite(credentials.write())
                    .block(timeout);
        }
    }

    /**
     * 换掉失效的客户端：别的调用已经换过时直接用新的。
     *
     * @param failed 本次调用用的客户端
     * @param credentials 本次调用的凭据，新客户端的初始化带上它
     * @return 已初始化的客户端
     */
    private synchronized McpClientWrapper reconnect(McpClientWrapper failed, McpCredentials credentials) {
        if (client != failed) {
            return client;
        }
        McpClientWrapper fresh = factory.apply(server);
        try {
            fresh.initialize().contextWrite(credentials.write()).block(initializationTimeout);
        } catch (RuntimeException e) {
            closeQuietly(fresh);
            throw e;
        }
        client = fresh;
        closeQuietly(failed);
        return fresh;
    }

    /**
     * 失败是否因为服务端不认这个会话：会话不存在（404）、会话已终止（SDK 关闭了旧会话中挂起的请求）、客户端初始化失败（加入了后台那次
     * 没有凭据的重新初始化）。
     *
     * @param error 调用的异常
     * @return 是时为 true
     */
    static boolean sessionLost(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof McpTransportSessionNotFoundException) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null
                    && (message.contains("MCP session with server terminated")
                            || message.contains("Client failed to initialize"))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeQuietly(client);
    }

    private void closeQuietly(McpClientWrapper wrapper) {
        try {
            wrapper.close();
        } catch (RuntimeException e) {
            log.debug("关闭 MCP 客户端失败 mcpServerId={} error={}", server.mcpServerId(), e.toString());
        }
    }
}
