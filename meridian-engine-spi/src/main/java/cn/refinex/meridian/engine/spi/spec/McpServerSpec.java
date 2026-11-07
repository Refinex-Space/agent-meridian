package cn.refinex.meridian.engine.spi.spec;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 智能体挂载的一个 MCP 服务（管理员在控制面注册），见架构设计「平台工具」「凭据」。
 *
 * <p>它的工具以 {@code mcp__{name}__{tool}} 的名字出现在智能体的工具集中。凭据只以凭据 ID 出现，
 * 明文在工具执行边界经 {@code SecretResolver} 解析后写入请求头，不进入模型上下文（架构原则第 6 条）。
 *
 * @param mcpServerId MCP 服务 ID（{@code mcp_…}）；必填
 * @param name 工具名前缀中的服务名，匹配 {@code ^[a-z][a-z0-9_]{0,31}$}；必填
 * @param url 服务端点，必须是 http 或 https；必填
 * @param transport 传输方式；必填
 * @param headerSecretIds 需要注入的请求头：头名 → 凭据 ID（{@code sec_…}）；可为空映射
 * @param sideEffect 管理员注册时标注的副作用等级，作用于该服务的全部工具；必填
 * @param enabledTools 启用的工具名（不含前缀）；空集合表示启用该服务的全部工具
 */
public record McpServerSpec(
        String mcpServerId,
        String name,
        String url,
        McpTransport transport,
        Map<String, String> headerSecretIds,
        SideEffect sideEffect,
        Set<String> enabledTools) {

    /** 服务名的格式：与平台工具名一样只用小写字母、数字、下划线，避免 {@code mcp__} 前缀的解析歧义。 */
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    /**
     * 创建 MCP 服务配置。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 服务名或端点格式不合法时
     */
    public McpServerSpec {
        Objects.requireNonNull(mcpServerId, "mcpServerId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(sideEffect, "sideEffect");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("MCP 服务名不合法：" + name);
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new IllegalArgumentException("MCP 服务端点必须是 http 或 https：" + mcpServerId);
        }
        headerSecretIds = headerSecretIds == null ? Map.of() : Map.copyOf(headerSecretIds);
        enabledTools = enabledTools == null ? Set.of() : Set.copyOf(enabledTools);
    }

    /** MCP 服务的传输方式。 */
    public enum McpTransport {
        /** Streamable HTTP。 */
        STREAMABLE_HTTP,
        /** HTTP + SSE（旧版传输）。 */
        SSE
    }
}
