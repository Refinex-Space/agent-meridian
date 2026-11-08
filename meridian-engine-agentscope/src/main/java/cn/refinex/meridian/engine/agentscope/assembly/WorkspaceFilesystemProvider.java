package cn.refinex.meridian.engine.agentscope.assembly;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import java.nio.file.Path;
import java.util.Map;

/**
 * 提供工作区文件系统的各个部件，装配器按环境档位组合（架构设计「文件系统装配」）：
 *
 * <ul>
 *   <li>{@code none} 档：{@code abstractFilesystem(CompositeFilesystem(platformStore, protectedRoutes))}。
 *   <li>{@code sandbox} 档：{@code filesystem(sandboxSpec)}，并用 {@code filesystemRoute} 挂载受保护路径与
 *       {@code large_tool_results/}；harness 包装为 {@code RoutedSandboxFilesystem}，{@code execute} 始终在沙箱内。
 * </ul>
 *
 * <p>生产实现由 T10a（{@code fs.PlatformWorkspace}：{@code MeridianBaseStore}、{@code ReadOnlyFilesystem}）与 T12a（Kubernetes
 * 沙箱）提供。
 * 文件系统的命名空间必须从 {@code PlatformContext} 取，不能取 {@code RuntimeContext.getSessionId()}（那是线程 ID）。
 */
public interface WorkspaceFilesystemProvider {

    /** 工具结果卸载的目录，两个档位都路由到平台存储（架构设计「上下文管理」）。 */
    String LARGE_TOOL_RESULTS_PREFIX = "/large_tool_results/";

    /**
     * {@code none} 档的默认后端：平台存储上的 {@code RemoteFilesystem}。
     *
     * @param profile 装配对象
     * @return 文件系统
     */
    AbstractFilesystem platformStore(AgentProfile profile);

    /**
     * 受保护路径的路由：路径前缀 → 只读文件系统，两个档位共用。前缀以 {@code /} 开头；目录前缀以 {@code /} 结尾
     * （例如 {@code /skills/}），单个文件不带结尾斜杠（例如 {@code /AGENTS.md}）。
     *
     * <p>注意 {@code CompositeFilesystem} 会剥离目录前缀：访问 {@code skills/a/SKILL.md} 时后端收到 {@code /a/SKILL.md}，
     * 所以每个目录前缀要有自己的后端视图。
     *
     * @param profile 装配对象
     * @return 路由表，按前缀登记顺序；不能为空
     */
    Map<String, AbstractFilesystem> protectedRoutes(AgentProfile profile);

    /**
     * {@code sandbox} 档中 {@value #LARGE_TOOL_RESULTS_PREFIX} 路由到的平台存储。后端收到的是剥离前缀后的路径。
     *
     * @param profile 装配对象
     * @return 文件系统
     */
    AbstractFilesystem largeToolResultStore(AgentProfile profile);

    /**
     * {@code sandbox} 档的沙箱文件系统规范（生产为 {@code KubernetesFilesystemSpec}）。会话沙箱由平台管理，每轮作为
     * 外部沙箱放进 {@code RuntimeContext}（架构设计「会话沙箱」），装配器会关闭默认的工作区投影。
     *
     * @param profile 装配对象，环境档位为 {@code sandbox}
     * @return 沙箱文件系统规范，每次装配一个新实例
     */
    SandboxFilesystemSpec sandboxSpec(AgentProfile profile);

    /**
     * 本机工作区根目录，装配时作为 {@code HarnessAgent.Builder.workspace(...)}（登记事项 D05）。不设置时 harness 取
     * {@code ${user.dir}/.agentscope/workspace}，并会从中读取第 3 层技能、本机回退的 {@code AGENTS.md} 等。平台要求会话数据不落
     * 本机磁盘：返回一个平台控制、平台不写入的目录（可以不存在），P4 的测试断言它在会话之后仍然没有文件。
     *
     * @return 本机工作区根目录
     */
    Path localWorkspaceRoot();
}
