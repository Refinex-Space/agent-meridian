package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.engine.agentscope.assembly.AgentAssembler;
import cn.refinex.meridian.engine.agentscope.assembly.AgentInstanceCache;
import cn.refinex.meridian.engine.agentscope.assembly.ModelFactory;
import cn.refinex.meridian.engine.agentscope.assembly.SkillRepositoryProvider;
import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import cn.refinex.meridian.engine.agentscope.assembly.WorkspaceFilesystemProvider;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformMiddlewareProvider;
import cn.refinex.meridian.engine.agentscope.state.FencedAgentStateStore;
import cn.refinex.meridian.engine.agentscope.turn.AgentScopeEngine;
import cn.refinex.meridian.engine.spi.engine.AgentEngine;
import cn.refinex.meridian.engine.spi.port.AgentConfigPort;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.engine.spi.port.InboxPort;
import cn.refinex.meridian.engine.spi.port.LeaseRegistry;
import cn.refinex.meridian.runtime.approval.app.ApprovalAudit;
import cn.refinex.meridian.runtime.dispatch.app.DispatchWakeups;
import cn.refinex.meridian.runtime.dispatch.app.TurnClaimer;
import cn.refinex.meridian.runtime.session.app.TurnFinisher;
import cn.refinex.meridian.runtime.session.app.TurnMetrics;
import cn.refinex.meridian.runtime.session.app.TurnPreparer;
import cn.refinex.meridian.runtime.session.app.TurnWorker;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.session.app.WorkerProperties;
import cn.refinex.meridian.runtime.stream.app.EventStreamPublisher;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * 装配 {@code meridian-engine-agentscope} 的内核与 Worker 的后台循环（架构设计「引擎装配」「一轮执行的生命周期」）。
 *
 * <p>内核需要四个提供者：模型工厂（T09a 的 {@code RoutedModel}）、工具（T10a 起）、文件系统（T10a、T12a）、技能仓库（T10a）。
 * 它们都存在时才装配内核与 Worker，所以写成自动配置（在用户配置之后求值 {@code @ConditionalOnBean}）：T06 时生产环境还没有这些
 * 提供者，runtime 照常启动、不认领轮次；测试与后续任务提供它们后即生效（ADR-0028）。
 *
 * <p>runtime 只在本包引用 {@code meridian-engine-agentscope} 的装配入口（ArchUnit 规则 2）。
 */
@AutoConfiguration
@ConditionalOnBean({
    ModelFactory.class,
    ToolProvider.class,
    WorkspaceFilesystemProvider.class,
    SkillRepositoryProvider.class
})
public class EngineAutoConfiguration {

    /**
     * 平台中间件，批次级 checkpoint 按 {@code meridian.runtime.worker.batch-checkpoint}（默认开启）。
     *
     * @param leases 本节点持有的租约
     * @param inbox 收件箱，steer 中间件使用
     * @param properties 执行配置
     * @return 中间件提供者
     */
    @Bean
    public PlatformMiddlewareProvider platformMiddlewareProvider(
            LeaseRegistry leases, InboxPort inbox, WorkerProperties properties) {
        return new PlatformMiddlewareProvider(leases, inbox, properties.batchCheckpoint());
    }

    /**
     * 带租约校验的状态存储（架构设计「租约与 fencing」）。
     *
     * @param states 状态端口的数据库实现
     * @param leases 本节点持有的租约
     * @return 状态存储
     */
    @Bean
    public FencedAgentStateStore fencedAgentStateStore(AgentStateRepository states, LeaseRegistry leases) {
        return new FencedAgentStateStore(states, leases);
    }

    /**
     * 智能体装配器。
     *
     * @param models 模型工厂
     * @param stateStore 状态存储
     * @param middlewares 平台中间件
     * @param tools 工具提供者
     * @param filesystems 文件系统提供者
     * @param skills 技能仓库提供者
     * @return 装配器
     */
    @Bean
    public AgentAssembler agentAssembler(
            ModelFactory models,
            FencedAgentStateStore stateStore,
            PlatformMiddlewareProvider middlewares,
            ToolProvider tools,
            WorkspaceFilesystemProvider filesystems,
            SkillRepositoryProvider skills) {
        return new AgentAssembler(models, stateStore, middlewares, tools, filesystems, skills);
    }

    /**
     * 智能体实例缓存，容器关闭时关闭全部实例。
     *
     * @param assembler 装配器
     * @return 实例缓存
     */
    @Bean(destroyMethod = "close")
    public AgentInstanceCache agentInstanceCache(AgentAssembler assembler) {
        return new AgentInstanceCache(assembler);
    }

    /**
     * 智能体内核。
     *
     * @param instances 实例缓存
     * @param configs 配置端口
     * @param leases 本节点持有的租约
     * @param blobs 大对象存储
     * @return 内核
     */
    @Bean
    public AgentScopeEngine agentEngine(
            AgentInstanceCache instances, AgentConfigPort configs, LeaseRegistry leases, BlobStorePort blobs) {
        return new AgentScopeEngine(instances, configs, leases, blobs);
    }

    /**
     * 轮次 Worker。
     *
     * @param claimer 认领用例
     * @param preparer 准备用例
     * @param finisher 收尾用例
     * @param engine 内核
     * @param audit 事件写入与权限判定审计
     * @param publisher 实时扇出
     * @param metrics 指标
     * @param clock 时钟
     * @return Worker
     */
    @Bean
    public TurnWorker turnWorker(
            TurnClaimer claimer,
            TurnPreparer preparer,
            TurnFinisher finisher,
            AgentEngine engine,
            ApprovalAudit audit,
            EventStreamPublisher publisher,
            TurnMetrics metrics,
            Clock clock) {
        return new TurnWorker(claimer, preparer, finisher, engine, audit, publisher, metrics, clock);
    }

    /**
     * Worker 的后台循环与节点优雅下线。
     *
     * @param worker Worker
     * @param wakeups 调度唤醒
     * @param properties 执行配置
     * @return 生命周期组件
     */
    @Bean
    public TurnWorkerLifecycle turnWorkerLifecycle(
            TurnWorker worker, DispatchWakeups wakeups, WorkerProperties properties) {
        return new TurnWorkerLifecycle(worker, wakeups, properties);
    }
}
