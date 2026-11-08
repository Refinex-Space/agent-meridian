package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.engine.agentscope.assembly.AssembledAgent;
import cn.refinex.meridian.engine.spi.engine.AgentEngine;
import cn.refinex.meridian.engine.spi.port.AgentConfigPort;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.engine.spi.port.SnapshotStorePort;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.persistence.jdbc.PeriodicJob;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.runtime.approval.app.PendingActionTimeouts;
import cn.refinex.meridian.runtime.sandbox.app.ObjectStoreSnapshotStore;
import cn.refinex.meridian.runtime.session.app.AgentConfigProvider;
import cn.refinex.meridian.runtime.session.app.ReservedToolNames;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.session.app.WorkerProperties;
import cn.refinex.meridian.runtime.stream.app.ObjectStoreBlobStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 轮次执行所需、与内核实现无关的装配：执行配置、智能体配置来源与 {@code AgentConfigPort}、大对象存储 {@code BlobStorePort}。
 * 内核本身与 Worker 的后台循环在 {@link EngineAutoConfiguration} 中装配。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfiguration {

    /** 内核未装配时的保留工具名：平台工具名与 harness 仍然注册的工具名。 */
    static final Set<String> STATIC_RESERVED_NAMES = Stream.concat(
                    PlatformToolNames.ALL.stream(), AssembledAgent.HARNESS_TOOL_NAMES.stream())
            .collect(Collectors.toUnmodifiableSet());

    /** 待处理动作超时扫描的间隔（秒）。 */
    static final long PENDING_ACTION_SCAN_SECONDS = 10;

    /** 读取保留工具名的等待上限：第一次需要装配智能体实例。 */
    private static final Duration RESERVED_NAMES_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 智能体配置来源：T06 从开发配置目录读取 YAML（{@code meridian.runtime.dev-agent-config.directory}，默认相对于工作目录的
     * {@code dev-config/agents}，即用 {@code scripts/dev/run-runtime.sh} 从仓库根目录启动时的位置）；T15b 起改为 control-plane 下发。
     *
     * @param directory 开发配置目录
     * @return 配置来源
     */
    @Bean
    public AgentConfigProvider agentConfigProvider(
            @Value("${meridian.runtime.dev-agent-config.directory:dev-config/agents}") Path directory) {
        return new FileAgentConfigProvider(directory);
    }

    /**
     * 内核读取配置的端口。
     *
     * @param provider 配置来源
     * @return 端口实现
     */
    @Bean
    public AgentConfigPort agentConfigPort(AgentConfigProvider provider) {
        return new ProvidedAgentConfigPort(provider);
    }

    /**
     * 大对象存储：超过 16 KB 的工具结果等写入对象存储（架构设计「端口」），替换 T04 测试用的内存实现。
     *
     * @param objects 对象存储客户端
     * @return 端口实现
     */
    @Bean
    public BlobStorePort blobStorePort(ObjectStore objects) {
        return new ObjectStoreBlobStore(objects);
    }

    /**
     * 沙箱工作区快照的存储（架构设计「端口」「会话沙箱」），由 T12a 装配的 {@code SandboxDriver} 使用。
     *
     * @param objects 对象存储客户端
     * @return 端口实现
     */
    @Bean
    public SnapshotStorePort snapshotStorePort(ObjectStore objects) {
        return new ObjectStoreSnapshotStore(objects);
    }

    /**
     * 保留工具名（架构设计「平台工具」）：内核已装配时取智能体装配后从 Toolkit 读出的名单（含平台工具与 harness 工具）；内核未装配时
     * （T09a、T10a 之前的生产环境不执行轮次）取平台工具名与 harness 工具名。
     *
     * @param engine 内核；未装配时为空
     * @return 保留工具名
     */
    @Bean
    public ReservedToolNames reservedToolNames(ObjectProvider<AgentEngine> engine) {
        return (agent, environment) -> {
            AgentEngine available = engine.getIfAvailable();
            if (available == null) {
                return STATIC_RESERVED_NAMES;
            }
            Set<String> names = available
                    .reservedToolNames(agent.agentVersionId(), environment.environmentId(), environment.revision())
                    .block(RESERVED_NAMES_TIMEOUT);
            return names == null ? STATIC_RESERVED_NAMES : names;
        };
    }

    /**
     * 待处理动作的超时扫描，每 {@value #PENDING_ACTION_SCAN_SECONDS} 秒一次（架构设计「待处理动作」）。超时默认 30 分钟，扫描间隔只
     * 决定超时结论晚到多久；多节点同时扫描是安全的（{@code SKIP LOCKED}）。
     *
     * @param timeouts 超时处理
     * @return 后台任务
     */
    @Bean
    public PeriodicJob pendingActionTimeoutJob(PendingActionTimeouts timeouts) {
        return new PeriodicJob(
                "pending-action-timeouts",
                Duration.ofSeconds(PENDING_ACTION_SCAN_SECONDS),
                timeouts::expireDue,
                TurnWorkerLifecycle.LEASE_JOBS_PHASE);
    }
}
