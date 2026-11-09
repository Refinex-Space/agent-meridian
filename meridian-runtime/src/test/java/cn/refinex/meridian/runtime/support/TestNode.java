package cn.refinex.meridian.runtime.support;

import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.approval.app.ApprovalAudit;
import cn.refinex.meridian.runtime.dispatch.app.DispatchProperties;
import cn.refinex.meridian.runtime.dispatch.app.LeaseReaper;
import cn.refinex.meridian.runtime.dispatch.app.LeaseRenewer;
import cn.refinex.meridian.runtime.dispatch.app.NodeLeaseRegistry;
import cn.refinex.meridian.runtime.dispatch.app.TurnClaimer;
import cn.refinex.meridian.runtime.dispatch.app.TurnEnqueuer;
import cn.refinex.meridian.runtime.dispatch.app.TurnTransitions;
import cn.refinex.meridian.runtime.dispatch.domain.NodeId;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnLeaseRepository;
import cn.refinex.meridian.runtime.dispatch.infra.system.TurnQueueRepository;
import cn.refinex.meridian.runtime.session.infra.system.JdbcAgentStateRepository;
import cn.refinex.meridian.runtime.stream.app.DatabaseEventSink;
import cn.refinex.meridian.runtime.stream.app.EventStreamPublisher;
import cn.refinex.meridian.runtime.stream.app.SessionEventWriter;
import cn.refinex.meridian.runtime.stream.infra.system.SessionEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 一个 runtime 节点的调度与写入对象，按生产的依赖关系手工装配。测试在同一个 JVM 中创建多个节点（节点 ID 不同、共享数据库
 * 与时钟）来模拟多节点竞争。唤醒通知用计数器代替 Redis。
 *
 * @param nodeId 节点 ID
 * @param registry 本节点的租约登记
 * @param enqueuer 入队
 * @param claimer 认领
 * @param renewer 续租
 * @param reaper 回收器
 * @param transitions 置回队列与结束
 * @param events 事件写入入口
 * @param eventSink {@code EventSinkPort} 的实现
 * @param states {@code AgentStateRepository} 的实现
 * @param queue 轮次队列仓储（读取轮次输入）
 * @param wakeups 已发布的唤醒通知次数
 * @param publisher 事件的实时扇出（Testcontainers 中的 Redis）
 * @param meters 本节点的指标注册表
 */
public record TestNode(
        NodeId nodeId,
        NodeLeaseRegistry registry,
        TurnEnqueuer enqueuer,
        TurnClaimer claimer,
        LeaseRenewer renewer,
        LeaseReaper reaper,
        TurnTransitions transitions,
        SessionEventWriter events,
        DatabaseEventSink eventSink,
        JdbcAgentStateRepository states,
        TurnQueueRepository queue,
        AtomicInteger wakeups,
        EventStreamPublisher publisher,
        SimpleMeterRegistry meters) {

    /**
     * 用默认调度配置创建节点。
     *
     * @param nodeId 节点 ID
     * @param clock 时钟，通常是多个节点共享的可控时钟
     * @return 节点
     */
    public static TestNode create(String nodeId, Clock clock) {
        return create(nodeId, clock, DispatchProperties.defaults());
    }

    /**
     * 创建节点。
     *
     * @param nodeId 节点 ID
     * @param clock 时钟
     * @param properties 调度配置
     * @return 节点
     */
    public static TestNode create(String nodeId, Clock clock, DispatchProperties properties) {
        return create(nodeId, clock, properties, RuntimeTestDatabase.dataSource());
    }

    /**
     * 创建使用指定数据源的节点，例如可以模拟数据库不可用的 {@link FailingDataSource}。
     *
     * @param nodeId 节点 ID
     * @param clock 时钟
     * @param properties 调度配置
     * @param dataSource 数据源
     * @return 节点
     */
    public static TestNode create(String nodeId, Clock clock, DispatchProperties properties, DataSource dataSource) {
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        NodeId node = new NodeId(nodeId);
        NodeLeaseRegistry registry = new NodeLeaseRegistry();
        TurnQueueRepository queue = new TurnQueueRepository(dataSource);
        TurnLeaseRepository leases = new TurnLeaseRepository(dataSource);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EventStreamPublisher publisher = new EventStreamPublisher(RuntimeTestRedis.reactiveMessaging(), clock, meters);
        SessionEventWriter events =
                new SessionEventWriter(new SessionEventRepository(dataSource), transactions, node, clock, publisher);
        TurnTransitions transitions = new TurnTransitions(leases, events, transactions, node, clock);
        AtomicInteger wakeups = new AtomicInteger();
        return new TestNode(
                node,
                registry,
                new TurnEnqueuer(queue, transactions, wakeups::incrementAndGet, properties, clock),
                new TurnClaimer(queue, registry, transactions, node, properties, clock),
                new LeaseRenewer(leases, transactions, registry, node, properties, clock),
                new LeaseReaper(transitions),
                transitions,
                events,
                new DatabaseEventSink(
                        new ApprovalAudit(events, new OutboxWriter(dataSource, "rt", clock), transactions, clock),
                        publisher),
                new JdbcAgentStateRepository(dataSource, transactions, node, clock),
                queue,
                wakeups,
                publisher,
                meters);
    }
}
