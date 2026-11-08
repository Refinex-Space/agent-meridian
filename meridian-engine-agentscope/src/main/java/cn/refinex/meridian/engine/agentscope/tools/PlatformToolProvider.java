package cn.refinex.meridian.engine.agentscope.tools;

import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import io.agentscope.core.tool.AgentTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 平台工具的提供者：按装配对象启用的工具名，用登记的工厂为每个实例创建一组新的平台工具（工具绑定所属实例的工作区，不跨实例
 * 共享）。T10a 登记六个文件工具；T10b、T12a、T13 的工具经 {@link #with} 加入同一个提供者。
 *
 * <p>装配对象启用了、但还没有实现的工具不注册，记一条告警：模型看不到它，也就调不到它。
 *
 * <p>线程安全：不可变。
 */
public final class PlatformToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(PlatformToolProvider.class);

    private final PlatformTool.ToolServices services;
    private final Map<String, Function<PlatformTool.ToolServices, PlatformTool>> factories;

    private PlatformToolProvider(
            PlatformTool.ToolServices services,
            Map<String, Function<PlatformTool.ToolServices, PlatformTool>> factories) {
        this.services = Objects.requireNonNull(services, "services");
        this.factories = Map.copyOf(factories);
    }

    /**
     * 登记了六个文件工具的提供者。
     *
     * @param services 平台服务
     * @return 提供者
     */
    public static PlatformToolProvider withFileTools(PlatformTool.ToolServices services) {
        Map<String, Function<PlatformTool.ToolServices, PlatformTool>> factories = new LinkedHashMap<>();
        factories.put(PlatformToolNames.READ_FILE, ReadFileTool::new);
        factories.put(PlatformToolNames.LIST_FILES, ListFilesTool::new);
        factories.put(PlatformToolNames.GLOB_FILES, GlobFilesTool::new);
        factories.put(PlatformToolNames.GREP_FILES, GrepFilesTool::new);
        factories.put(PlatformToolNames.WRITE_FILE, WriteFileTool::new);
        factories.put(PlatformToolNames.EDIT_FILE, EditFileTool::new);
        return new PlatformToolProvider(services, factories);
    }

    /**
     * 返回多登记一个工具的提供者。
     *
     * @param name 工具名，取自 {@code PlatformToolNames}
     * @param factory 工厂
     * @return 新的提供者
     */
    public PlatformToolProvider with(String name, Function<PlatformTool.ToolServices, PlatformTool> factory) {
        Map<String, Function<PlatformTool.ToolServices, PlatformTool>> copy = new LinkedHashMap<>(factories);
        copy.put(name, factory);
        return new PlatformToolProvider(services, copy);
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
            Function<PlatformTool.ToolServices, PlatformTool> factory = factories.get(name);
            if (factory != null) {
                PlatformTool tool = factory.apply(services);
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
        return ToolBundle.of(tools);
    }
}
