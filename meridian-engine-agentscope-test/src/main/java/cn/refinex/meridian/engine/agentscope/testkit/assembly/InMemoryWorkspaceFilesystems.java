package cn.refinex.meridian.engine.agentscope.testkit.assembly;

import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.assembly.WorkspaceFilesystemProvider;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版的工作区文件系统提供者：平台存储、各个受保护路径、大结果目录各是一个独立的
 * {@link RemoteFilesystem}（底层为 {@link RecordingBaseStore}），{@code sandbox} 档用 {@link FakeSandboxFilesystemSpec}。
 *
 * <p>同一个提供者每次返回同样的文件系统实例，测试据此断言装配结果的路由。受保护路径的列表取自架构设计
 * 「文件系统装配」：{@code AGENTS.md}、{@code skills/}、{@code subagents/}、{@code tools.json}、{@code MEMORY.md}。
 */
public final class InMemoryWorkspaceFilesystems implements WorkspaceFilesystemProvider {

    /** 受保护路径的路由前缀，单个文件不带结尾斜杠。 */
    public static final List<String> PROTECTED_PREFIXES =
            List.of("/AGENTS.md", "/skills/", "/subagents/", "/tools.json", "/MEMORY.md");

    /** 测试用的本机工作区根，装配后不应出现任何文件。 */
    public static final Path LOCAL_WORKSPACE_ROOT =
            Path.of(System.getProperty("java.io.tmpdir"), "meridian-test-agent-workspace");

    private final RecordingBaseStore platformBackend = new RecordingBaseStore();
    private final AbstractFilesystem platformStore = new RemoteFilesystem(platformBackend);
    private final AbstractFilesystem largeToolResults = new RemoteFilesystem(new RecordingBaseStore());
    private final Map<String, AbstractFilesystem> protectedRoutes = new LinkedHashMap<>();
    private final List<FakeSandboxFilesystemSpec> sandboxSpecs = new CopyOnWriteArrayList<>();

    /** 创建提供者，为每个受保护路径准备一个独立的内存文件系统。 */
    public InMemoryWorkspaceFilesystems() {
        for (String prefix : PROTECTED_PREFIXES) {
            protectedRoutes.put(prefix, new RemoteFilesystem(new RecordingBaseStore()));
        }
    }

    @Override
    public AbstractFilesystem platformStore(AgentProfile profile) {
        return platformStore;
    }

    @Override
    public Map<String, AbstractFilesystem> protectedRoutes(AgentProfile profile) {
        return Collections.unmodifiableMap(protectedRoutes);
    }

    @Override
    public AbstractFilesystem largeToolResultStore(AgentProfile profile) {
        return largeToolResults;
    }

    @Override
    public SandboxFilesystemSpec sandboxSpec(AgentProfile profile) {
        FakeSandboxFilesystemSpec spec = new FakeSandboxFilesystemSpec();
        sandboxSpecs.add(spec);
        return spec;
    }

    /**
     * 测试用的本机工作区根：系统临时目录下一个不会被创建的目录。
     *
     * @return 目录
     */
    @Override
    public Path localWorkspaceRoot() {
        return LOCAL_WORKSPACE_ROOT;
    }

    /**
     * 返回过的沙箱文件系统规范，按装配顺序。
     *
     * @return 规范
     */
    public List<FakeSandboxFilesystemSpec> sandboxSpecs() {
        return List.copyOf(sandboxSpecs);
    }

    /**
     * 平台存储的底层存储，用于断言访问记录。
     *
     * @return 底层存储
     */
    public RecordingBaseStore platformBackend() {
        return platformBackend;
    }

    /**
     * 某个受保护路径的文件系统。
     *
     * @param prefix 路由前缀，取自 {@link #PROTECTED_PREFIXES}
     * @return 文件系统
     */
    public AbstractFilesystem protectedRoute(String prefix) {
        return protectedRoutes.get(prefix);
    }

    /**
     * 大结果目录的文件系统。
     *
     * @return 文件系统
     */
    public AbstractFilesystem largeToolResults() {
        return largeToolResults;
    }

    /**
     * 平台存储的文件系统。
     *
     * @return 文件系统
     */
    public AbstractFilesystem platformStore() {
        return platformStore;
    }
}
