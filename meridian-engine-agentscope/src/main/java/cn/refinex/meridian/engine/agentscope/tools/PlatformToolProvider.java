package cn.refinex.meridian.engine.agentscope.tools;

import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import cn.refinex.meridian.engine.agentscope.tools.mcp.McpConnector;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import io.agentscope.core.tool.AgentTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 平台工具的提供者：按装配对象启用的工具名，用登记的工厂为每个实例创建一组新的平台工具（工具绑定所属实例的工作区，不跨实例
 * 共享）。T10a 登记六个文件工具；T10b、T12a、T13 的工具经 {@link #with} 加入同一个提供者；工厂可以读取装配对象（例如
 * {@code knowledge_search} 取智能体绑定的知识库）。挂载了 MCP 服务时，经 {@link #withMcp} 登记的 {@link McpConnector} 连接服务、
 * 发现工具，客户端随实例关闭（T10b，ADR-0035）。
 *
 * <p>装配对象启用了、但还没有实现的工具不注册，记一条告警：模型看不到它，也就调不到它。
 *
 * <p>线程安全：不可变。
 */
public final class PlatformToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(PlatformToolProvider.class);

    private final PlatformTool.ToolServices services;
    private final Map<String, BiFunction<PlatformTool.ToolServices, AgentProfile, PlatformTool>> factories;
    private final McpConnector mcp;

    private PlatformToolProvider(
            PlatformTool.ToolServices services,
            Map<String, BiFunction<PlatformTool.ToolServices, AgentProfile, PlatformTool>> factories,
            McpConnector mcp) {
        this.services = Objects.requireNonNull(services, "services");
        this.factories = Map.copyOf(factories);
        this.mcp = mcp;
    }

    /**
     * 登记了六个文件工具的提供者。
     *
     * @param services 平台服务
     * @return 提供者
     */
    public static PlatformToolProvider withFileTools(PlatformTool.ToolServices services) {
        return new PlatformToolProvider(services, Map.of(), null)
                .with(PlatformToolNames.READ_FILE, ReadFileTool::new)
                .with(PlatformToolNames.LIST_FILES, ListFilesTool::new)
                .with(PlatformToolNames.GLOB_FILES, GlobFilesTool::new)
                .with(PlatformToolNames.GREP_FILES, GrepFilesTool::new)
                .with(PlatformToolNames.WRITE_FILE, WriteFileTool::new)
                .with(PlatformToolNames.EDIT_FILE, EditFileTool::new);
    }

    /**
     * 返回多登记一个工具的提供者。
     *
     * @param name 工具名，取自 {@code PlatformToolNames}
     * @param factory 工厂
     * @return 新的提供者
     */
    public PlatformToolProvider with(String name, Function<PlatformTool.ToolServices, PlatformTool> factory) {
        Objects.requireNonNull(factory, "factory");
        return with(name, (toolServices, profile) -> factory.apply(toolServices));
    }

    /**
     * 返回多登记一个工具的提供者，工厂可以读取装配对象。
     *
     * @param name 工具名，取自 {@code PlatformToolNames}
     * @param factory 工厂：平台服务与装配对象 → 工具；返回 null 表示该工具在这个装配对象上不可用（例如 {@code bash} 在
     *     {@code none} 档）
     * @return 新的提供者
     */
    public PlatformToolProvider with(
            String name, BiFunction<PlatformTool.ToolServices, AgentProfile, PlatformTool> factory) {
        Map<String, BiFunction<PlatformTool.ToolServices, AgentProfile, PlatformTool>> copy =
                new LinkedHashMap<>(factories);
        copy.put(name, Objects.requireNonNull(factory, "factory"));
        return new PlatformToolProvider(services, copy, mcp);
    }

    /**
     * 返回连接 MCP 服务的提供者。
     *
     * @param connector MCP 连接器
     * @return 新的提供者
     */
    public PlatformToolProvider withMcp(McpConnector connector) {
        return new PlatformToolProvider(services, factories, Objects.requireNonNull(connector, "connector"));
    }

    /**
     * 已登记的工具名。
     *
     * @return 工具名
     */
    public java.util.Set<String> names() {
        return factories.keySet();
    }

    @Override
    public ToolBundle tools(AgentProfile profile) {
        List<AgentTool> tools = new ArrayList<>();
        for (String name : new TreeSet<>(profile.tools())) {
            BiFunction<PlatformTool.ToolServices, AgentProfile, PlatformTool> factory = factories.get(name);
            if (factory != null) {
                PlatformTool tool = factory.apply(services, profile);
                if (tool == null) {
                    // 工具在本装配对象上不可用，例如 bash 只在 sandbox 档注册
                    log.debug(
                            "平台工具在本环境不可用，未注册 agentVersionId={} tool={}",
                            profile.key().agentVersionId(),
                            name);
                    continue;
                }
                if (!tool.getName().equals(name)) {
                    throw new IllegalStateException("工具工厂登记为 " + name + "，创建的工具名是 " + tool.getName());
                }
                tools.add(tool);
            } else if (PlatformToolNames.ALL.contains(name)) {
                log.warn(
                        "智能体启用了尚未实现的平台工具，未注册 agentVersionId={} tool={}",
                        profile.key().agentVersionId(),
                        name);
            }
        }
        if (mcp == null) {
            if (!profile.agent().mcpServers().isEmpty()) {
                log.warn(
                        "智能体挂载了 MCP 服务但没有登记 MCP 连接器，未注册 MCP 工具 agentVersionId={}",
                        profile.key().agentVersionId());
            }
            return ToolBundle.of(tools);
        }
        ToolBundle mcpTools = mcp.connect(profile, services);
        tools.addAll(mcpTools.tools());
        return new ToolBundle(tools, mcpTools.resources());
    }
}
