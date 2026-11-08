package cn.refinex.meridian.engine.agentscope.tools.mcp;

import cn.refinex.meridian.engine.spi.port.SecretValue;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import reactor.util.context.Context;

/**
 * 一次 MCP 请求要注入的请求头：头名 → 凭据明文。经 Reactor Context 的 {@link McpTransportContext} 交给
 * {@link McpCredentialInjector}，只在这一次调用的订阅链上存在，不写进客户端、工具定义、提示词与日志（架构原则第 6 条，ADR-0035）。
 *
 * <p>{@link #toString()} 只输出头名：MCP SDK 在调试日志中可能打印传输上下文。
 */
public final class McpCredentials {

    /** {@link McpTransportContext} 中保存本对象的键。 */
    static final String CONTEXT_KEY = "meridian.mcp.credentials";

    private static final McpCredentials NONE = new McpCredentials(Map.of());

    private final Map<String, SecretValue> headers;

    private McpCredentials(Map<String, SecretValue> headers) {
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    /**
     * 创建请求头集合。
     *
     * @param headers 头名 → 凭据明文
     * @return 集合
     */
    public static McpCredentials of(Map<String, SecretValue> headers) {
        return headers.isEmpty() ? NONE : new McpCredentials(headers);
    }

    /**
     * 没有请求头的集合。
     *
     * @return 空集合
     */
    public static McpCredentials none() {
        return NONE;
    }

    /**
     * 要注入的请求头。
     *
     * @return 头名 → 凭据明文
     */
    Map<String, SecretValue> headers() {
        return headers;
    }

    /**
     * 写入 Reactor Context 的函数，供 {@code contextWrite} 使用。
     *
     * @return 写入函数
     */
    public Function<Context, Context> write() {
        McpTransportContext transport = McpTransportContext.create(Map.of(CONTEXT_KEY, this));
        return context -> context.put(McpTransportContext.KEY, transport);
    }

    @Override
    public String toString() {
        return "McpCredentials" + headers.keySet();
    }
}
