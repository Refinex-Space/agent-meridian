package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.engine.agentscope.assembly.AgentAssembler;
import cn.refinex.meridian.engine.agentscope.assembly.AgentInstanceCache;
import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.assembly.TenantPromptProvider;
import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import cn.refinex.meridian.engine.agentscope.fs.PlatformWorkspace;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformMiddlewareProvider;
import cn.refinex.meridian.engine.agentscope.sandbox.SandboxAttachment;
import cn.refinex.meridian.engine.agentscope.sandbox.SandboxDriverSettings;
import cn.refinex.meridian.engine.agentscope.sandbox.TurnSandboxes;
import cn.refinex.meridian.engine.agentscope.state.FencedAgentStateStore;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.RecordingMiddlewareProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.RecordingModelFactory;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticToolProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestAssembly;
import cn.refinex.meridian.engine.agentscope.tools.BashTool;
import cn.refinex.meridian.engine.agentscope.tools.PlatformTool;
import cn.refinex.meridian.engine.agentscope.tools.PlatformToolProvider;
import cn.refinex.meridian.engine.agentscope.turn.AgentScopeEngine;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.approval.app.ApprovalAudit;
import cn.refinex.meridian.runtime.approval.app.PendingActionResolver;
import cn.refinex.meridian.runtime.approval.app.PermissionPolicies;
import cn.refinex.meridian.runtime.approval.infra.DatabasePolicyStore;
import cn.refinex.meridian.runtime.approval.infra.PendingActionRepository;
import cn.refinex.meridian.runtime.approval.infra.PermissionRuleRepository;
import cn.refinex.meridian.runtime.delivery.app.DatabaseInbox;
import cn.refinex.meridian.runtime.delivery.app.EventIntake;
import cn.refinex.meridian.runtime.delivery.infra.InboxRepository;
import cn.refinex.meridian.runtime.dispatch.app.DispatchProperties;
import cn.refinex.meridian.runtime.dispatch.app.DispatchWakeups;
import cn.refinex.meridian.runtime.dispatch.app.NodeChannel;
import cn.refinex.meridian.runtime.dispatch.app.NodeChannelListener;
import cn.refinex.meridian.runtime.sandbox.app.DatabaseSandboxStates;
import cn.refinex.meridian.runtime.sandbox.app.SandboxProperties;
import cn.refinex.meridian.runtime.sandbox.app.SandboxService;
import cn.refinex.meridian.runtime.sandbox.infra.SandboxOutputRepository;
import cn.refinex.meridian.runtime.sandbox.infra.SessionSandboxRepository;
import cn.refinex.meridian.runtime.session.app.TurnExecution;
import cn.refinex.meridian.runtime.session.app.TurnFinisher;
import cn.refinex.meridian.runtime.session.app.TurnMetrics;
import cn.refinex.meridian.runtime.session.app.TurnPreparer;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.session.app.TurnWorker;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.session.app.WorkerProperties;
import cn.refinex.meridian.runtime.session.infra.SessionRepository;
import cn.refinex.meridian.runtime.session.infra.ThreadRepository;
import cn.refinex.meridian.runtime.session.infra.system.ThreadLockRepository;
import cn.refinex.meridian.runtime.stream.app.ObjectStoreBlobStore;
import cn.refinex.meridian.runtime.support.RuntimeTestObjectStore;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import cn.refinex.meridian.runtime.support.TestNode;
import cn.refinex.meridian.runtime.tooling.app.DatabaseToolLedger;
import cn.refinex.meridian.runtime.tooling.infra.ToolInvocationRepository;
import cn.refinex.meridian.runtime.workspace.app.DatabaseWorkspaceStore;
import cn.refinex.meridian.runtime.workspace.infra.system.WorkspaceEntryRepository;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.AgentTool;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Schedulers;

/**
 * 一个完整的 runtime 节点：T05 的调度与写入（{@link TestNode}）加上 T06 的收件箱、工具账本、大对象存储、真实装配的
 * {@code AgentScopeEngine}（{@code ScriptedModel} 驱动）与 Worker。多个节点共享数据库、Redis、MinIO、时钟与模型脚本，
 * 用不同的节点 ID 模拟多节点（T06「实现要点」）。
 *
 * @param node 调度与写入
 * @param engine 本节点的内核
 * @param worker 本节点的 Worker
 * @param inbox 收件箱
 * @param ledger 工具账本
 * @param finisher 收尾用例
 * @param tools 本节点的记账工具：工具名 → 实例
 * @param states 本节点状态存储使用的状态端口（可能被测试包装）
 * @param properties 执行配置
 * @param intake 发送事件的用例（模拟 API：投递、中断、回应与恢复轮）
 * @param resolver 待处理动作的结论与恢复轮
 * @param channelListener 本节点频道 {@code node:{node_id}} 的订阅，测试负责启停
 */
record WorkerNode(
        TestNode node,
        AgentScopeEngine engine,
        TurnWorker worker,
        DatabaseInbox inbox,
        DatabaseToolLedger ledger,
        TurnFinisher finisher,
        Map<String, LedgerTool> tools,
        AgentStateRepository states,
        WorkerProperties properties,
        EventIntake intake,
        PendingActionResolver resolver,
        NodeChannelListener channelListener,
        SandboxService sandboxes) {

    /** 等待一次执行结束的上限。 */
    static final Duration RUN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 节点的组装参数。
     *
     * @param nodeId 节点 ID
     * @param clock 共享的时钟
     * @param model 共享的模型脚本
     * @param profile 夹具智能体的装配对象，平台中间件按它创建（压缩守护的外层要知道压缩预留）
     * @param agents 共享的智能体配置
     * @param tools 记账工具：工具名 → (副作用等级, 跨节点共用的调用记录)
     * @param properties 执行配置
     * @param states 包装状态端口（例如记录被拒绝的写入）；不包装时为恒等函数
     * @param dataSource 本节点的数据源（例如可以模拟数据库不可用的 {@code FailingDataSource}）
     * @param extraTools 不记账的其他测试工具（例如实现了 {@code matchRule} 的路径工具）
     * @param platform 是否用生产的平台工具（文件工具）、工作区（{@code PlatformWorkspace} 加 {@code DatabaseWorkspaceStore}）
     *     与技能仓库装配（T10a）；否则文件系统与技能仓库是内存替身
     */
    record Spec(
            String nodeId,
            Clock clock,
            Model model,
            AgentProfile profile,
            TestAgents agents,
            Map<String, ToolSpec> tools,
            WorkerProperties properties,
            UnaryOperator<AgentStateRepository> states,
            DataSource dataSource,
            List<AgentTool> extraTools,
            boolean platform,
            SandboxWiring sandbox) {}

    /**
     * {@code sandbox} 档的装配（T12a）：沙箱驱动（kind 集群中的 {@code KubernetesSandboxDriver}）与配置。平台模式下才生效。
     *
     * @param driver 沙箱驱动
     * @param attachment 按状态取得沙箱，交给 {@code TurnSandboxes}
     * @param properties 沙箱配置
     */
    record SandboxWiring(SandboxDriver driver, SandboxAttachment attachment, SandboxProperties properties) {}

    /**
     * 一个记账工具的定义。
     *
     * @param sideEffect 副作用等级
     * @param calls 跨节点共用的调用记录与闸门
     */
    record ToolSpec(SideEffect sideEffect, LedgerTool.Calls calls) {}

    static WorkerNode create(Spec spec) {
        DataSource dataSource = spec.dataSource();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TestNode node = TestNode.create(spec.nodeId(), spec.clock(), DispatchProperties.defaults(), dataSource);
        TurnMetrics metrics = new TurnMetrics(node.meters());
        DatabaseInbox inbox = new DatabaseInbox(
                new InboxRepository(dataSource), node.events(), transactions, node.nodeId(), spec.clock());
        DatabaseToolLedger ledger = new DatabaseToolLedger(
                new ToolInvocationRepository(dataSource),
                transactions,
                new OutboxWriter(dataSource, "rt", spec.clock()),
                node.meters(),
                node.nodeId(),
                spec.clock());
        BlobStorePort blobs = new ObjectStoreBlobStore(RuntimeTestObjectStore.get());

        Map<String, LedgerTool> tools = new LinkedHashMap<>();
        spec.tools()
                .forEach((name, tool) ->
                        tools.put(name, new LedgerTool(name, tool.sideEffect(), ledger, blobs, tool.calls())));
        AgentStateRepository states = spec.states().apply(node.states());
        List<MiddlewareBase> middlewares = new ArrayList<>(new PlatformMiddlewareProvider(
                        node.registry(), inbox, spec.properties().batchCheckpoint())
                .middlewares(spec.profile()));
        ProvidedAgentConfigPort configs = new ProvidedAgentConfigPort(spec.agents());
        AgentAssembler assembler;
        if (spec.platform()) {
            // 生产的平台工具、工作区与技能仓库（T10a）：文件进 rt.workspace_entries 与 MinIO
            PlatformWorkspace workspace = new PlatformWorkspace(
                    new DatabaseWorkspaceStore(
                            new WorkspaceEntryRepository(dataSource),
                            RuntimeTestObjectStore.get(),
                            transactions,
                            spec.clock()),
                    configs,
                    WorkerFixture.LOCAL_WORKSPACE_ROOT,
                    spec.sandbox() == null
                            ? null
                            : () -> TurnSandboxes.filesystemSpec(
                                    SandboxDriverSettings.defaults(new SandboxDriverSettings.PortForward())));
            ToolProvider platformTools = PlatformToolProvider.withFileTools(
                            new PlatformTool.ToolServices(ledger, blobs))
                    .with(PlatformToolNames.BASH, BashTool.factory(BashTool.Settings.DEFAULTS));
            List<AgentTool> testTools = allTools(tools, spec.extraTools());
            ToolProvider combined = profile -> {
                List<AgentTool> all =
                        new ArrayList<>(platformTools.tools(profile).tools());
                all.addAll(testTools);
                return ToolProvider.ToolBundle.of(all);
            };
            assembler = new AgentAssembler(
                    new RecordingModelFactory(spec.model()),
                    new FencedAgentStateStore(states, node.registry()),
                    new RecordingMiddlewareProvider(middlewares, middlewares),
                    combined,
                    workspace,
                    workspace.skills(),
                    TenantPromptProvider.fromPort(configs));
        } else {
            assembler = TestAssembly.builder(spec.model())
                    .stateStore(new FencedAgentStateStore(states, node.registry()))
                    .middlewares(new RecordingMiddlewareProvider(middlewares, middlewares))
                    .tools(new StaticToolProvider(List.of(), allTools(tools, spec.extraTools())))
                    .tenantPrompts(TenantPromptProvider.fromPort(configs))
                    .build()
                    .assembler();
        }
        SandboxService sandboxes = null;
        TurnSandboxes turnSandboxes = null;
        if (spec.sandbox() != null) {
            turnSandboxes = new TurnSandboxes(
                    new DatabaseSandboxStates(new SessionSandboxRepository(dataSource), transactions, spec.clock()),
                    spec.sandbox().attachment());
            sandboxes = new SandboxService(
                    new StaticListableBeanFactory(
                                    Map.of("driver", spec.sandbox().driver()))
                            .getBeanProvider(SandboxDriver.class),
                    configs,
                    new SessionSandboxRepository(dataSource),
                    new SandboxOutputRepository(dataSource),
                    RuntimeTestObjectStore.get(),
                    new OutboxWriter(dataSource, "rt", spec.clock()),
                    transactions,
                    spec.sandbox().properties(),
                    node.meters(),
                    spec.clock());
        }
        AgentScopeEngine engine = new AgentScopeEngine(
                new AgentInstanceCache(assembler), configs, node.registry(), blobs, spec.clock(), turnSandboxes);

        PendingActionRepository pendingActions = new PendingActionRepository(dataSource);
        PermissionRuleRepository rules = new PermissionRuleRepository(dataSource);
        ApprovalAudit approvalAudit = new ApprovalAudit(
                node.events(), new OutboxWriter(dataSource, "rt", spec.clock()), transactions, spec.clock());
        PendingActionResolver resolver = new PendingActionResolver(
                pendingActions,
                rules,
                new DatabasePolicyStore(rules, spec.clock()),
                node.transitions(),
                node.enqueuer(),
                node.claimer(),
                approvalAudit,
                spec.clock());
        TurnFinisher finisher = new TurnFinisher(
                node.transitions(),
                node.enqueuer(),
                node.events(),
                inbox,
                pendingActions,
                resolver,
                approvalAudit,
                new ThreadLockRepository(dataSource),
                new OutboxWriter(dataSource, "rt", spec.clock()),
                transactions,
                spec.properties(),
                metrics,
                node.nodeId(),
                spec.clock());
        TurnPreparer preparer = new TurnPreparer(
                node.claimer(),
                new SessionRepository(dataSource),
                spec.agents(),
                node.states(),
                ledger,
                node.events(),
                inbox,
                blobs,
                finisher,
                metrics,
                permissions(dataSource, spec.clock()),
                pendingActions,
                transactions);
        TurnWorker worker = new TurnWorker(
                node.claimer(),
                preparer,
                finisher,
                engine,
                approvalAudit,
                node.publisher(),
                metrics,
                sandboxes,
                spec.clock());
        NodeChannel channel = new NodeChannel(RuntimeTestRedis.reactiveMessaging());
        EventIntake intake = new EventIntake(
                new SessionRepository(dataSource),
                new ThreadRepository(dataSource),
                new ThreadLockRepository(dataSource),
                pendingActions,
                node.enqueuer(),
                node.transitions(),
                inbox,
                resolver,
                channel,
                node.events(),
                transactions);
        return new WorkerNode(
                node,
                engine,
                worker,
                inbox,
                ledger,
                finisher,
                tools,
                states,
                spec.properties(),
                intake,
                resolver,
                new NodeChannelListener(channel, node.renewer(), node.nodeId(), 0),
                sandboxes);
    }

    private static List<AgentTool> allTools(Map<String, LedgerTool> ledgerTools, List<AgentTool> extraTools) {
        List<AgentTool> all = new ArrayList<>(ledgerTools.values());
        all.addAll(extraTools);
        return all;
    }

    /** 权限合成：开发实现的策略存储与会话级规则都在 {@code rt.permission_rules}。 */
    static PermissionPolicies permissions(DataSource dataSource, Clock clock) {
        PermissionRuleRepository rules = new PermissionRuleRepository(dataSource);
        return new PermissionPolicies(new DatabasePolicyStore(rules, clock), rules);
    }

    /**
     * 认领一个轮次并执行到结束。
     *
     * @return 结果
     * @throws IllegalStateException 没有可执行的轮次时
     */
    TurnResult claimAndRun() {
        return claim().run().block(RUN_TIMEOUT);
    }

    /**
     * 认领一个轮次。
     *
     * @return 待执行的轮次
     * @throws IllegalStateException 没有可执行的轮次时
     */
    TurnExecution claim() {
        return worker.claim().orElseThrow(() -> new IllegalStateException(node.nodeId() + " 没有可认领的轮次"));
    }

    /**
     * 本节点的后台循环与下线组件：唤醒信号每 100 毫秒轮询一次（不依赖通知），模拟生产中的 Worker 循环。
     *
     * @return 生命周期组件，测试负责启停
     */
    TurnWorkerLifecycle lifecycle() {
        DispatchWakeups wakeups = new DispatchWakeups(
                RuntimeTestRedis.reactiveMessaging(), Duration.ofMillis(100), Schedulers.parallel());
        return new TurnWorkerLifecycle(worker, wakeups, properties);
    }

    /** 默认调度配置，供需要它的测试读取租约时长。 */
    static DispatchProperties dispatch() {
        return DispatchProperties.defaults();
    }
}
