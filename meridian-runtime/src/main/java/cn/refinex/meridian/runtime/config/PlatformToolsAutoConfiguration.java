package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.engine.agentscope.assembly.SkillRepositoryProvider;
import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import cn.refinex.meridian.engine.agentscope.assembly.WorkspaceFilesystemProvider;
import cn.refinex.meridian.engine.agentscope.fs.PlatformWorkspace;
import cn.refinex.meridian.engine.agentscope.tools.PlatformTool;
import cn.refinex.meridian.engine.agentscope.tools.PlatformToolProvider;
import cn.refinex.meridian.engine.spi.port.AgentConfigPort;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort;
import cn.refinex.meridian.engine.spi.port.WorkspaceStorePort;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 内核的工具、工作区文件系统与技能仓库三个提供者的生产实现（T10a）：平台工具（{@link PlatformToolProvider}，经账本与对象存储
 * 端口）、{@code none} 档工作区与平台技能仓库（{@link PlatformWorkspace}，经工作区存储与配置端口）。排在
 * {@link EngineAutoConfiguration} 之前；测试自己声明某个提供者时不创建对应的生产实现。
 *
 * <p>工作区与技能仓库各是一个 bean：{@code PlatformWorkspace} 同时实现两个接口，作为一个 bean 注册时按接口注入会出现两个候选，
 * 所以技能仓库的 bean 是委托给它的方法引用。
 */
@AutoConfiguration(before = EngineAutoConfiguration.class)
public class PlatformToolsAutoConfiguration {

    /**
     * 平台工具（T10a 的六个文件工具；T10b、T12a、T13 经 {@code PlatformToolProvider.with} 加入）。
     *
     * @param ledger 工具账本
     * @param blobs 大对象存储
     * @return 工具提供者
     */
    @Bean
    @ConditionalOnMissingBean(ToolProvider.class)
    public ToolProvider platformToolProvider(ToolLedgerPort ledger, BlobStorePort blobs) {
        return PlatformToolProvider.withFileTools(new PlatformTool.ToolServices(ledger, blobs));
    }

    /**
     * {@code none} 档的工作区文件系统（{@code sandbox} 档由 T12a 提供）。
     *
     * @param store 工作区存储
     * @param configs 配置端口，读取技能内容
     * @param localWorkspaceRoot 平台控制的本机工作区根目录（登记事项 D05），默认在系统临时目录下；平台不在其中写入，目录可以
     *     不存在
     * @return 文件系统提供者
     */
    @Bean
    @ConditionalOnMissingBean(WorkspaceFilesystemProvider.class)
    public WorkspaceFilesystemProvider platformWorkspace(
            WorkspaceStorePort store,
            AgentConfigPort configs,
            @Value("${meridian.runtime.workspace.local-root:${java.io.tmpdir}/meridian/agent-workspace}")
                    Path localWorkspaceRoot) {
        return new PlatformWorkspace(store, configs, localWorkspaceRoot);
    }

    /**
     * 平台技能仓库：按装配对象的技能引用，经配置端口取技能内容。
     *
     * @param store 工作区存储
     * @param configs 配置端口
     * @param localWorkspaceRoot 本机工作区根目录
     * @return 技能仓库提供者
     */
    @Bean
    @ConditionalOnMissingBean(SkillRepositoryProvider.class)
    public SkillRepositoryProvider platformSkillRepository(
            WorkspaceStorePort store,
            AgentConfigPort configs,
            @Value("${meridian.runtime.workspace.local-root:${java.io.tmpdir}/meridian/agent-workspace}")
                    Path localWorkspaceRoot) {
        PlatformWorkspace workspace = new PlatformWorkspace(store, configs, localWorkspaceRoot);
        return workspace::repository;
    }
}
