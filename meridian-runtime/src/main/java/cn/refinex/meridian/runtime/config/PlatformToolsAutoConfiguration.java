package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.engine.agentscope.assembly.SkillRepositoryProvider;
import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import cn.refinex.meridian.engine.agentscope.assembly.WorkspaceFilesystemProvider;
import cn.refinex.meridian.engine.agentscope.fs.PlatformWorkspace;
import cn.refinex.meridian.engine.agentscope.tools.KnowledgeSearchTool;
import cn.refinex.meridian.engine.agentscope.tools.PlatformTool;
import cn.refinex.meridian.engine.agentscope.tools.PlatformToolProvider;
import cn.refinex.meridian.engine.agentscope.tools.WebFetchTool;
import cn.refinex.meridian.engine.agentscope.tools.WebSearchTool;
import cn.refinex.meridian.engine.agentscope.tools.mcp.McpConnector;
import cn.refinex.meridian.engine.spi.port.AgentConfigPort;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.engine.spi.port.KnowledgeSearchPort;
import cn.refinex.meridian.engine.spi.port.SecretResolver;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort;
import cn.refinex.meridian.engine.spi.port.WebFetchPort;
import cn.refinex.meridian.engine.spi.port.WebSearchPort;
import cn.refinex.meridian.engine.spi.port.WorkspaceStorePort;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.runtime.session.app.AgentConfigProvider;
import cn.refinex.meridian.runtime.tooling.app.TenantWebSearch;
import cn.refinex.meridian.runtime.tooling.app.UnconfiguredKnowledgeSearch;
import cn.refinex.meridian.runtime.tooling.infra.BochaSearchAdapter;
import cn.refinex.meridian.runtime.tooling.infra.GuardedWebFetcher;
import cn.refinex.meridian.runtime.tooling.infra.SearxngSearchAdapter;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 内核的工具、工作区文件系统与技能仓库三个提供者的生产实现（T10a）：平台工具（{@link PlatformToolProvider}，经账本与对象存储
 * 端口；T10b 起另有联网、知识检索与 MCP，以及它们用到的 {@code WebFetchPort}、{@code WebSearchPort}、{@code KnowledgeSearchPort}）、{@code none} 档工作区与平台技能仓库（{@link PlatformWorkspace}，经工作区存储与配置端口）。排在
 * {@link EngineAutoConfiguration} 之前；测试自己声明某个提供者时不创建对应的生产实现。
 *
 * <p>工作区与技能仓库各是一个 bean，技能仓库经 {@code PlatformWorkspace.skills()} 取得，两个 bean 各自只有一个提供者类型。
 */
@AutoConfiguration(before = EngineAutoConfiguration.class)
public class PlatformToolsAutoConfiguration {

    /**
     * 平台工具：T10a 的六个文件工具；T10b 的 {@code web_fetch}、{@code web_search}、{@code knowledge_search} 与 MCP 工具（智能体挂载的
     * MCP 服务在装配时连接）；T12a、T13 经 {@code PlatformToolProvider.with} 继续加入。
     *
     * @param ledger 工具账本
     * @param blobs 大对象存储
     * @param webFetch 网页抓取
     * @param webSearch 联网搜索
     * @param knowledge 知识库检索
     * @param secrets 凭据解析，MCP 请求头用
     * @return 工具提供者
     */
    @Bean
    @ConditionalOnMissingBean(ToolProvider.class)
    public ToolProvider platformToolProvider(
            ToolLedgerPort ledger,
            BlobStorePort blobs,
            WebFetchPort webFetch,
            WebSearchPort webSearch,
            KnowledgeSearchPort knowledge,
            SecretResolver secrets) {
        return PlatformToolProvider.withFileTools(new PlatformTool.ToolServices(ledger, blobs))
                .with(PlatformToolNames.WEB_FETCH, services -> new WebFetchTool(services, webFetch))
                .with(PlatformToolNames.WEB_SEARCH, services -> new WebSearchTool(services, webSearch))
                .with(
                        PlatformToolNames.KNOWLEDGE_SEARCH,
                        (services, profile) -> new KnowledgeSearchTool(
                                services, knowledge, profile.agent().knowledge().knowledgeBaseIds()))
                .withMcp(new McpConnector(secrets));
    }

    /**
     * 带 SSRF 防护的网页抓取（{@code tooling.infra.GuardedWebFetcher}，ADR-0035）。
     *
     * @return 抓取端口
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(WebFetchPort.class)
    public GuardedWebFetcher webFetchPort() {
        return new GuardedWebFetcher();
    }

    /**
     * 按租户选择后端的联网搜索：SearXNG 与博查两个适配器（ADR-0035）。
     *
     * @param configs 租户设置来源
     * @param secrets 凭据解析
     * @return 搜索端口
     */
    @Bean
    @ConditionalOnMissingBean(WebSearchPort.class)
    public WebSearchPort webSearchPort(AgentConfigProvider configs, SecretResolver secrets) {
        return new TenantWebSearch(configs, secrets, List.of(new SearxngSearchAdapter(), new BochaSearchAdapter()));
    }

    /**
     * 知识库检索：T19 之前是"未配置"的实现，检索以错误结果交给模型。
     *
     * @return 检索端口
     */
    @Bean
    @ConditionalOnMissingBean(KnowledgeSearchPort.class)
    public KnowledgeSearchPort knowledgeSearchPort() {
        return new UnconfiguredKnowledgeSearch();
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
        return new PlatformWorkspace(store, configs, localWorkspaceRoot).skills();
    }
}
