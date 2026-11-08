package cn.refinex.meridian.engine.agentscope.tools.mcp;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import cn.refinex.meridian.engine.agentscope.tools.PlatformTool;
import cn.refinex.meridian.engine.spi.error.McpUnavailableException;
import cn.refinex.meridian.engine.spi.port.SecretResolver;
import cn.refinex.meridian.engine.spi.port.SecretValue;
import cn.refinex.meridian.engine.spi.spec.McpServerSpec;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按智能体版本挂载的 MCP 服务建立客户端、发现工具（架构设计「平台工具」「凭据」，ADR-0035）。装配智能体实例时调用一次，客户端作为
 * {@link ToolProvider.ToolBundle#resources()} 随实例缓存创建与关闭。
 *
 * <p>客户端用 2.0.4 的 {@link McpClientBuilder} 构建，顺序固定：先选择传输（Streamable HTTP 或 SSE），再设置 HTTP 客户端与请求定制器
 * ——这几个方法只对已选择的传输生效，先设置会被静默忽略；显式声明协议版本。不经 {@code Toolkit.registerMcpClient}：每个 MCP 工具包成
 * {@link McpPlatformTool}，与平台工具一样进权限引擎、记账本。
 *
 * <p>凭据：配置中只有凭据 ID，每次请求（含初始化与工具发现）之前经 {@link SecretResolver} 解析，放进这一次调用的 Reactor Context，由
 * {@link McpCredentialInjector} 写入请求头；不进入客户端配置、工具定义、提示词与日志。
 *
 * <p>SSRF：MCP 端点由管理员登记，可以是内网地址（企业 MCP 服务通常在内网）；客户端不跟随重定向，并拒绝解析到链路本地地址
 * （含云元数据 {@code 169.254.169.254}）或已知云元数据地址的端点。
 *
 * <p>线程安全：无状态，可并发调用。{@link #connect} 会阻塞（DNS 解析、初始化握手），只在装配时的虚拟线程上调用。
 */
public final class McpConnector {

    private static final Logger log = LoggerFactory.getLogger(McpConnector.class);

    /** 单次 MCP 请求的超时，与 2.0.4 {@code McpClientBuilder} 的默认值一致。 */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /** 初始化握手的超时。 */
    static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(30);

    /** 建立连接的超时。 */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 解析一个凭据的最长等待。 */
    static final Duration SECRET_TIMEOUT = Duration.ofSeconds(30);

    /** Streamable HTTP 传输声明的协议版本：MCP SDK 0.17.2 的客户端与服务端都支持这两个。 */
    static final List<String> STREAMABLE_HTTP_PROTOCOL_VERSIONS = List.of("2025-03-26", "2025-06-18");

    /** SSE 传输只有 2024-11-05 一个协议版本。 */
    static final List<String> SSE_PROTOCOL_VERSIONS = List.of("2024-11-05");

    /** 模型供应商对工具名的通用约束：字母、数字、下划线、短横线，至多 64 个字符。 */
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /** 不在链路本地段内的已知云元数据地址：阿里云 {@code 100.100.100.200}、AWS IPv6 {@code fd00:ec2::254}。 */
    private static final Set<String> METADATA_ADDRESSES = Set.of("100.100.100.200", "fd00:ec2:0:0:0:0:0:254");

    private final SecretResolver secrets;

    /**
     * 创建连接器。
     *
     * @param secrets 凭据解析
     */
    public McpConnector(SecretResolver secrets) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /**
     * MCP 工具在智能体中的名字。
     *
     * @param server 服务配置
     * @param tool 服务端的工具名
     * @return {@code mcp__{server}__{tool}}
     */
    public static String toolName(McpServerSpec server, String tool) {
        return PlatformToolNames.MCP_PREFIX + server.name() + "__" + tool;
    }

    /**
     * 为装配对象连接它用到的 MCP 服务并发现工具。主智能体取每个服务启用的工具（未限定时为全部）；子智能体只取它工具集中列出的 MCP
     * 工具。工具按名字排序，保证同一智能体的工具定义顺序稳定。
     *
     * @param profile 装配对象
     * @param services 平台服务
     * @return 工具与需要随实例关闭的客户端
     * @throws McpUnavailableException 服务连不上、初始化或工具发现失败时（可重试）
     * @throws IllegalStateException 装配时没有 {@code PlatformContext}、凭据不可用或端点指向元数据地址时
     */
    public ToolProvider.ToolBundle connect(AgentProfile profile, PlatformTool.ToolServices services) {
        List<McpServerSpec> servers = profile.agent().mcpServers().stream()
                .filter(server -> wanted(profile, server))
                .toList();
        if (servers.isEmpty()) {
            return ToolProvider.ToolBundle.of(List.of());
        }
        PlatformContext platform = PlatformContexts.find()
                .orElseThrow(() -> new IllegalStateException(
                        "装配挂载 MCP 服务的智能体时没有 PlatformContext：" + profile.key().agentVersionId()));
        List<AgentTool> tools = new ArrayList<>();
        List<AutoCloseable> clients = new ArrayList<>();
        try {
            for (McpServerSpec server : servers) {
                checkEndpoint(server);
                McpClientWrapper client = build(server);
                clients.add(client);
                for (McpSchema.Tool tool : discover(server, client, credentials(server, platform))) {
                    String name = toolName(server, tool.name());
                    if (!TOOL_NAME.matcher(name).matches()) {
                        log.warn("MCP 工具名不符合模型的工具名约束，未注册 mcpServerId={} tool={}", server.mcpServerId(), name);
                    } else if (enabled(profile, server, tool.name())) {
                        tools.add(new McpPlatformTool(services, server, tool, client, this));
                    }
                }
            }
        } catch (RuntimeException e) {
            closeQuietly(clients, e);
            throw e;
        }
        log.info(
                "MCP 工具已发现 agentVersionId={} servers={} tools={}",
                profile.key().agentVersionId(),
                servers.size(),
                tools.size());
        return new ToolProvider.ToolBundle(tools, clients);
    }

    /**
     * 解析一个服务的请求头凭据，供一次请求使用。
     *
     * @param server 服务配置
     * @param platform 平台上下文，凭据按其中的项目解析
     * @return 请求头
     * @throws IllegalStateException 凭据不可用时；消息不含凭据内容
     */
    McpCredentials credentials(McpServerSpec server, PlatformContext platform) {
        if (server.headerSecretIds().isEmpty()) {
            return McpCredentials.none();
        }
        Map<String, SecretValue> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : new TreeMap<>(server.headerSecretIds()).entrySet()) {
            SecretValue value;
            try {
                value = secrets.resolve(header.getValue())
                        .contextWrite(PlatformContexts.write(platform))
                        .block(SECRET_TIMEOUT);
            } catch (RuntimeException e) {
                throw new IllegalStateException("MCP 服务 " + server.name() + " 的请求头 " + header.getKey() + " 的凭据不可用", e);
            }
            if (value == null) {
                throw new IllegalStateException("MCP 服务 " + server.name() + " 的请求头 " + header.getKey() + " 的凭据为空");
            }
            headers.put(header.getKey(), value);
        }
        return McpCredentials.of(headers);
    }

    /** 构建客户端（不发请求）。先选传输，再设置 HTTP 客户端、请求定制器与协议版本（2.0.4 中这些方法只对已选择的传输生效）。 */
    static McpClientWrapper build(McpServerSpec server) {
        McpClientBuilder builder = McpClientBuilder.create("meridian-" + server.name());
        switch (server.transport()) {
            case STREAMABLE_HTTP ->
                builder.streamableHttpTransport(server.url())
                        .customizeStreamableHttpClient(McpConnector::configureHttpClient)
                        .protocolVersions(STREAMABLE_HTTP_PROTOCOL_VERSIONS.toArray(String[]::new));
            case SSE ->
                builder.sseTransport(server.url())
                        .customizeSseClient(McpConnector::configureHttpClient)
                        .protocolVersions(SSE_PROTOCOL_VERSIONS.toArray(String[]::new));
        }
        return builder.httpRequestCustomizer(new McpCredentialInjector())
                .timeout(REQUEST_TIMEOUT)
                .initializationTimeout(INITIALIZATION_TIMEOUT)
                // 异步客户端：调用方的 Reactor Context（含本次请求的凭据）沿订阅链传到传输层；同步客户端在内部另起订阅，上下文传不过去
                .buildAsync()
                .block();
    }

    private static void configureHttpClient(HttpClient.Builder http) {
        http.followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT_TIMEOUT);
    }

    private static List<McpSchema.Tool> discover(
            McpServerSpec server, McpClientWrapper client, McpCredentials credentials) {
        try {
            client.initialize().contextWrite(credentials.write()).block(INITIALIZATION_TIMEOUT.plusSeconds(5));
            List<McpSchema.Tool> listed =
                    client.listTools().contextWrite(credentials.write()).block(REQUEST_TIMEOUT);
            return (listed == null ? List.<McpSchema.Tool>of() : listed)
                    .stream().sorted(Comparator.comparing(McpSchema.Tool::name)).toList();
        } catch (RuntimeException e) {
            log.warn("连接 MCP 服务失败 mcpServerId={} name={}", server.mcpServerId(), server.name(), e);
            throw new McpUnavailableException("MCP 服务 " + server.name() + " 暂时不可用", e);
        }
    }

    /** 拒绝解析到链路本地或已知云元数据地址的端点；解析失败按服务不可用处理。 */
    private static void checkEndpoint(McpServerSpec server) {
        String host = URI.create(server.url()).getHost();
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new McpUnavailableException("MCP 服务 " + server.name() + " 的主机名无法解析", e);
        }
        for (InetAddress address : addresses) {
            if (address.isLinkLocalAddress() || METADATA_ADDRESSES.contains(address.getHostAddress())) {
                throw new IllegalStateException("MCP 服务 " + server.name() + " 的端点指向链路本地或云元数据地址，拒绝连接");
            }
        }
    }

    private static boolean wanted(AgentProfile profile, McpServerSpec server) {
        if (!profile.isSubagent()) {
            return true;
        }
        String prefix = PlatformToolNames.MCP_PREFIX + server.name() + "__";
        return profile.tools().stream().anyMatch(tool -> tool.startsWith(prefix));
    }

    private static boolean enabled(AgentProfile profile, McpServerSpec server, String tool) {
        if (!server.enabledTools().isEmpty() && !server.enabledTools().contains(tool)) {
            return false;
        }
        return !profile.isSubagent() || profile.tools().contains(toolName(server, tool));
    }

    private static void closeQuietly(List<AutoCloseable> clients, RuntimeException cause) {
        for (AutoCloseable client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                cause.addSuppressed(e);
            }
        }
    }
}
