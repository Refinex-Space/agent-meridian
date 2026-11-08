package cn.refinex.meridian.engine.agentscope.fs;

import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.assembly.SkillRepositoryProvider;
import cn.refinex.meridian.engine.agentscope.assembly.WorkspaceFilesystemProvider;
import cn.refinex.meridian.engine.spi.port.AgentConfigPort;
import cn.refinex.meridian.engine.spi.port.WorkspaceStorePort;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.engine.spi.spec.SkillRef;
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 平台的工作区与技能仓库提供者（T10a 起 {@code none} 档，T12a 起 {@code sandbox} 档）：
 *
 * <ul>
 *   <li>默认后端：{@code RemoteFilesystem(MeridianBaseStore)}，命名空间 {@link WorkspaceNamespaces#FACTORY}，所有会话文件进
 *       {@code rt.workspace_entries} 与对象存储。
 *   <li>受保护路径：{@link ProtectedContent} 按装配对象生成的只读文件系统。
 *   <li>技能仓库：{@link MeridianSkillRepository}，读取同一份受保护内容中的 {@code skills/}。
 * </ul>
 *
 * <p>受保护内容在装配时经 {@link AgentConfigPort#skill} 取技能，装配在虚拟线程上执行，可以阻塞。{@code sandbox} 档（T12a）
 * 的主文件系统是会话沙箱，受保护路径与 {@code large_tool_results/} 仍由本类提供，经 {@code filesystemRoute} 挂载。
 *
 * <p>线程安全：无状态。
 */
public final class PlatformWorkspace implements WorkspaceFilesystemProvider {

    /** 装配时读取一个技能的超时。 */
    static final Duration SKILL_READ_TIMEOUT = Duration.ofSeconds(30);

    private final WorkspaceStorePort store;
    private final AgentConfigPort configs;
    private final Path localWorkspaceRoot;
    private final Supplier<SandboxFilesystemSpec> sandboxSpecs;

    /**
     * 创建只支持 {@code none} 档的提供者。
     *
     * @param store 工作区存储端口
     * @param configs 配置端口，读取技能内容
     * @param localWorkspaceRoot 平台控制的本机工作区根目录（登记事项 D05），harness 的本机回退读取都落在这里；平台不在其中写入
     */
    public PlatformWorkspace(WorkspaceStorePort store, AgentConfigPort configs, Path localWorkspaceRoot) {
        this(store, configs, localWorkspaceRoot, null);
    }

    /**
     * 创建提供者。
     *
     * @param store 工作区存储端口
     * @param configs 配置端口，读取技能内容
     * @param localWorkspaceRoot 平台控制的本机工作区根目录（登记事项 D05），harness 的本机回退读取都落在这里；平台不在其中写入
     * @param sandboxSpecs {@code sandbox} 档的沙箱文件系统规范，每次调用给一个新实例（T12a：{@code TurnSandboxes.filesystemSpec}）；
     *     为 null 时不支持 {@code sandbox} 档
     */
    public PlatformWorkspace(
            WorkspaceStorePort store,
            AgentConfigPort configs,
            Path localWorkspaceRoot,
            Supplier<SandboxFilesystemSpec> sandboxSpecs) {
        this.store = Objects.requireNonNull(store, "store");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.localWorkspaceRoot = Objects.requireNonNull(localWorkspaceRoot, "localWorkspaceRoot");
        this.sandboxSpecs = sandboxSpecs;
    }

    @Override
    public AbstractFilesystem platformStore(AgentProfile profile) {
        return new RemoteFilesystem(new MeridianBaseStore(store), WorkspaceNamespaces.FACTORY);
    }

    @Override
    public Map<String, AbstractFilesystem> protectedRoutes(AgentProfile profile) {
        return content(profile).routes();
    }

    @Override
    public AbstractFilesystem largeToolResultStore(AgentProfile profile) {
        return new RemoteFilesystem(new MeridianBaseStore(store), WorkspaceNamespaces.FACTORY);
    }

    @Override
    public SandboxFilesystemSpec sandboxSpec(AgentProfile profile) {
        if (sandboxSpecs == null) {
            throw new UnsupportedOperationException(
                    "没有配置会话沙箱，不能装配 sandbox 档的智能体：" + profile.key().agentId());
        }
        return sandboxSpecs.get();
    }

    @Override
    public Path localWorkspaceRoot() {
        return localWorkspaceRoot;
    }

    /**
     * 平台技能仓库的提供者：读取同一份受保护内容中的 {@code skills/}。不让本类直接实现 {@link SkillRepositoryProvider}：
     * 同时实现两个提供者接口的对象作为 Spring bean 时，按接口注入会出现两个候选。
     *
     * @return 技能仓库提供者
     */
    public SkillRepositoryProvider skills() {
        return this::repository;
    }

    /**
     * 装配对象的技能仓库。
     *
     * @param profile 装配对象
     * @return 技能仓库
     */
    public RuntimeContextSkillRepository repository(AgentProfile profile) {
        return new MeridianSkillRepository(content(profile).skillsFilesystem());
    }

    /**
     * 按装配对象生成受保护内容：逐个读取技能。
     *
     * @param profile 装配对象
     * @return 受保护内容
     */
    ProtectedContent content(AgentProfile profile) {
        List<SkillDefinition> skills =
                profile.skills().stream().map(this::skill).toList();
        return ProtectedContent.of(profile, skills);
    }

    private SkillDefinition skill(SkillRef ref) {
        SkillDefinition skill = configs.skill(ref).block(SKILL_READ_TIMEOUT);
        if (skill == null) {
            throw new IllegalStateException("技能不存在：" + ref.skillId() + "@" + ref.version());
        }
        if (!skill.name().equals(ref.name())) {
            throw new IllegalStateException(
                    "技能 " + ref.skillId() + "@" + ref.version() + " 的名字是 " + skill.name() + "，智能体版本引用为 " + ref.name());
        }
        return skill;
    }
}
