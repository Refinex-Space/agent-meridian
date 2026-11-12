package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.engine.agentscope.assembly.ModelFactory;
import cn.refinex.meridian.engine.agentscope.model.AnthropicClientFactory;
import cn.refinex.meridian.engine.agentscope.model.GeminiClientFactory;
import cn.refinex.meridian.engine.agentscope.model.OpenAiCompatibleClientFactory;
import cn.refinex.meridian.engine.agentscope.model.ProviderClients;
import cn.refinex.meridian.engine.agentscope.model.ProviderHttpTransport;
import cn.refinex.meridian.engine.agentscope.model.RoutedModel;
import cn.refinex.meridian.engine.agentscope.model.RoutedModelFactory;
import cn.refinex.meridian.engine.agentscope.model.RoutedModelInvoker;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker;
import cn.refinex.meridian.engine.spi.port.ModelGateway;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 生产用的模型工厂：{@code RoutedModelFactory}，底层客户端用 AgentScope 的 OpenAI 兼容模型类与平台的 HTTP 传输（架构设计「引擎装配」
 * 中的 {@code model} 行、「模型调用」）；OpenAI 兼容直通接口的 {@link ModelInvoker} 与它共享底层客户端缓存（T09b）。
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
    @ConditionalOnMissingBean
    public ProviderHttpTransport providerHttpTransport(Clock clock) {
        return new ProviderHttpTransport(clock);
    }

    /**
     * 底层客户端的登记与缓存，智能体的模型与直通接口共享。
     *
     * @param transport 供应商 HTTP 传输（OpenAI 兼容）
     * @param clock 时钟（Anthropic 的 {@code retry-after}，ADR-0046）
     * @return 登记表
     */
    @Bean
    @ConditionalOnMissingBean
    public ProviderClients providerClients(ProviderHttpTransport transport, Clock clock) {
        return new ProviderClients(List.of(
                new OpenAiCompatibleClientFactory(transport),
                new AnthropicClientFactory(clock),
                new GeminiClientFactory(clock)));
    }

    /**
     * 模型工厂。
     *
     * @param gateway 模型调用的平台侧服务
     * @param clients 底层客户端
     * @return 模型工厂
     */
    @Bean
    @ConditionalOnMissingBean(ModelFactory.class)
    public ModelFactory routedModelFactory(ModelGateway gateway, ProviderClients clients) {
        return new RoutedModelFactory(gateway, clients, RoutedModel.RetryPolicy.DEFAULT);
    }

    /**
     * 直通接口的聊天调用，经 {@code RoutedModel} 限流、预扣与计量。
     *
     * @param gateway 模型调用的平台侧服务
     * @param clients 底层客户端
     * @return 聊天调用
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelInvoker modelInvoker(ModelGateway gateway, ProviderClients clients) {
        return new RoutedModelInvoker(gateway, clients, RoutedModel.RetryPolicy.DEFAULT);
    }
}
