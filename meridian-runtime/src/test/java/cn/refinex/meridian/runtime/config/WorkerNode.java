package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.engine.agentscope.assembly.AgentInstanceCache;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformMiddlewareProvider;
import cn.refinex.meridian.engine.agentscope.state.FencedAgentStateStore;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.RecordingMiddlewareProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticToolProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestAssembly;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.turn.AgentScopeEngine;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.approval.infra.PendingActionRepository;
import cn.refinex.meridian.runtime.delivery.app.DatabaseInbox;
import cn.refinex.meridian.runtime.delivery.infra.InboxRepository;
import cn.refinex.meridian.runtime.dispatch.app.DispatchProperties;
import cn.refinex.meridian.runtime.dispatch.app.DispatchWakeups;
import cn.refinex.meridian.runtime.session.app.TurnExecution;
import cn.refinex.meridian.runtime.session.app.TurnFinisher;
import cn.refinex.meridian.runtime.session.app.TurnMetrics;
import cn.refinex.meridian.runtime.session.app.TurnPreparer;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.session.app.TurnWorker;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.session.app.WorkerProperties;
import cn.refinex.meridian.runtime.session.infra.SessionRepository;
import cn.refinex.meridian.runtime.session.infra.system.ThreadLockRepository;
import cn.refinex.meridian.runtime.stream.app.ObjectStoreBlobStore;
import cn.refinex.meridian.runtime.support.RuntimeTestObjectStore;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import cn.refinex.meridian.runtime.support.TestNode;
import cn.refinex.meridian.runtime.tooling.app.DatabaseToolLedger;
import cn.refinex.meridian.runtime.tooling.infra.ToolInvocationRepository;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.AgentTool;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
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
        WorkerProperties properties) {

    /** 等待一次执行结束的上限。 */
    static final Duration RUN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 节点的组装参数。
     *
     * @param nodeId 节点 ID
     * @param clock 共享的时钟
     * @param model 共享的模型脚本
     * @param agents 共享的智能体配置
     * @param tools 记账工具：工具名 → (副作用等级, 跨节点共用的调用记录)
     * @param properties 执行配置
     * @param states 包装状态端口（例如记录被拒绝的写入）；不包装时为恒等函数
     * @param dataSource 本节点的数据源（例如可以模拟数据库不可用的 {@code FailingDataSource}）
     */
    record Spec(
            String nodeId,
            Clock clock,
            ScriptedModel model,
            TestAgents agents,
            Map<String, ToolSpec> tools,
            WorkerProperties properties,
            UnaryOperator<AgentStateRepository> states,
            DataSource dataSource) {}

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
        DatabaseInbox inbox =
                new DatabaseInbox(new InboxRepository(dataSource), transactions, node.nodeId(), spec.clock());
        DatabaseToolLedger ledger = new DatabaseToolLedger(
                new ToolInvocationRepository(dataSource), transactions, node.nodeId(), spec.clock());
        BlobStorePort blobs = new ObjectStoreBlobStore(RuntimeTestObjectStore.get());

        Map<String, LedgerTool> tools = new LinkedHashMap<>();
        spec.tools()
                .forEach((name, tool) ->
                        tools.put(name, new LedgerTool(name, tool.sideEffect(), ledger, blobs, tool.calls())));
        AgentStateRepository states = spec.states().apply(node.states());
        List<MiddlewareBase> middlewares = new ArrayList<>(new PlatformMiddlewareProvider(
                        node.registry(), spec.properties().batchCheckpoint())
                .middlewares(null));
        TestAssembly assembly = TestAssembly.builder(spec.model())
                .stateStore(new FencedAgentStateStore(states, node.registry()))
                .middlewares(new RecordingMiddlewareProvider(middlewares, middlewares))
                .tools(new StaticToolProvider(List.of(), new ArrayList<AgentTool>(tools.values())))
                .build();
        AgentScopeEngine engine = new AgentScopeEngine(
                new AgentInstanceCache(assembly.assembler()),
                new ProvidedAgentConfigPort(spec.agents()),
                node.registry(),
                blobs);

        TurnFinisher finisher = new TurnFinisher(
                node.transitions(),
                node.enqueuer(),
                node.events(),
                inbox,
                new PendingActionRepository(dataSource),
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
                transactions);
        TurnWorker worker = new TurnWorker(
                node.claimer(), preparer, finisher, engine, node.events(), node.publisher(), metrics, spec.clock());
        return new WorkerNode(node, engine, worker, inbox, ledger, finisher, tools, states, spec.properties());
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
