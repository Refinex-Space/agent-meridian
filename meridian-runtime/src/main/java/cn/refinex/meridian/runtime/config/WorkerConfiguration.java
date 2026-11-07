package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.engine.spi.port.AgentConfigPort;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.runtime.session.app.AgentConfigProvider;
import cn.refinex.meridian.runtime.session.app.WorkerProperties;
import cn.refinex.meridian.runtime.stream.app.ObjectStoreBlobStore;
import java.nio.file.Path;
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
}
