package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.engine.agentscope.assembly.ModelFactory;
import cn.refinex.meridian.engine.agentscope.model.OpenAiCompatibleClientFactory;
import cn.refinex.meridian.engine.agentscope.model.ProviderClients;
import cn.refinex.meridian.engine.agentscope.model.ProviderHttpTransport;
import cn.refinex.meridian.engine.agentscope.model.RoutedModel;
import cn.refinex.meridian.engine.agentscope.model.RoutedModelFactory;
import cn.refinex.meridian.engine.spi.port.ModelGateway;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 生产用的模型工厂：{@code RoutedModelFactory}，底层客户端用 AgentScope 的 OpenAI 兼容模型类与平台的 HTTP 传输（架构设计「引擎装配」
 * 中的 {@code model} 行、「模型调用」）。
 *
 * <p>写成自动配置并排在 {@link EngineAutoConfiguration} 之前：内核在四个提供者都存在时才装配，模型工厂是其中之一；测试自己声明了
 * {@link ModelFactory}（例如 {@code ScriptedModel}）时不创建。runtime 只在本包引用 {@code meridian-engine-agentscope}（ArchUnit 规则 2）。
 */
@AutoConfiguration(before = EngineAutoConfiguration.class)
public class ModelFactoryAutoConfiguration {

    /**
     * 供应商 HTTP 传输，所有底层客户端共享，容器关闭时关闭。
     *
     * @param clock 时钟
     * @return 传输
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ModelFactory.class)
    public ProviderHttpTransport providerHttpTransport(Clock clock) {
        return new ProviderHttpTransport(clock);
    }

    /**
     * 模型工厂。
     *
     * @param gateway 模型调用的平台侧服务
     * @param transport 供应商 HTTP 传输
     * @return 模型工厂
     */
    @Bean
    @ConditionalOnMissingBean(ModelFactory.class)
    public ModelFactory routedModelFactory(ModelGateway gateway, ProviderHttpTransport transport) {
        ProviderClients clients = new ProviderClients(List.of(new OpenAiCompatibleClientFactory(transport)));
        return new RoutedModelFactory(gateway, clients, RoutedModel.RetryPolicy.DEFAULT);
    }
}
