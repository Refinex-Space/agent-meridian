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

/**
 * 平台的工作区与技能仓库提供者（T10a，{@code none} 档）：
 *
 * <ul>
 *   <li>默认后端：{@code RemoteFilesystem(MeridianBaseStore)}，命名空间 {@link WorkspaceNamespaces#FACTORY}，所有会话文件进
 *       {@code rt.workspace_entries} 与对象存储。
 *   <li>受保护路径：{@link ProtectedContent} 按装配对象生成的只读文件系统。
 *   <li>技能仓库：{@link MeridianSkillRepository}，读取同一份受保护内容中的 {@code skills/}。
 * </ul>
 *
 * <p>受保护内容在装配时经 {@link AgentConfigPort#skill} 取技能，装配在虚拟线程上执行，可以阻塞。{@code sandbox} 档的沙箱规格由
 * T12a 提供，本类不支持。
 *
 * <p>线程安全：无状态。
 */
public final class PlatformWorkspace implements WorkspaceFilesystemProvider, SkillRepositoryProvider {

    /** 装配时读取一个技能的超时。 */
    static final Duration SKILL_READ_TIMEOUT = Duration.ofSeconds(30);

    private final WorkspaceStorePort store;
    private final AgentConfigPort configs;
    private final Path localWorkspaceRoot;

    /**
     * 创建提供者。
     *
     * @param store 工作区存储端口
     * @param configs 配置端口，读取技能内容
     * @param localWorkspaceRoot 平台控制的本机工作区根目录（登记事项 D05），harness 的本机回退读取都落在这里；平台不在其中写入
     */
    public PlatformWorkspace(WorkspaceStorePort store, AgentConfigPort configs, Path localWorkspaceRoot) {
        this.store = Objects.requireNonNull(store, "store");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.localWorkspaceRoot = Objects.requireNonNull(localWorkspaceRoot, "localWorkspaceRoot");
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
        throw new UnsupportedOperationException("sandbox 档的文件系统由 T12a 提供");
    }

    @Override
    public Path localWorkspaceRoot() {
        return localWorkspaceRoot;
    }

    @Override
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
