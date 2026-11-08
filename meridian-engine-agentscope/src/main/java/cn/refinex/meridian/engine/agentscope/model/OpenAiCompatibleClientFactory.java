package cn.refinex.meridian.engine.agentscope.model;

import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelRoute;
import cn.refinex.meridian.engine.spi.port.ModelGateway.Provider;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.Objects;

/**
 * OpenAI 兼容接口的底层客户端：AgentScope 2.0.4 的 {@link OpenAIChatModel}，DashScope 走它的
 * {@code compatible-mode/v1} 端点（架构设计「模型调用」、测试规范「真实模型测试」）。
 *
 * <p>客户端用平台的 {@link ProviderHttpTransport}，构造时不放凭据；端点、凭据与模型名在每次调用的选项中给出
 * （{@code OpenAIChatModel} 以调用选项优先）。上下文窗口不在底层客户端上设置，由 {@link RoutedModel} 报告。
 */
public final class OpenAiCompatibleClientFactory implements ProviderClientFactory {

    private final HttpTransport transport;

    /**
     * 创建工厂。
     *
     * @param transport 供应商 HTTP 传输，所有客户端共享
     */
    public OpenAiCompatibleClientFactory(HttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public Provider provider() {
        return Provider.OPENAI_COMPATIBLE;
    }

    @Override
    public Model create(ModelRoute route) {
        return OpenAIChatModel.builder().modelName(route.upstreamModel()).baseUrl(route.baseUrl()).stream(true)
                .httpTransport(transport)
                .build();
    }

    @Override
    public GenerateOptions callOptions(ModelRoute route, GenerateOptions options) {
        GenerateOptions.Builder credentials =
                GenerateOptions.builder().modelName(route.upstreamModel()).baseUrl(route.baseUrl());
        if (route.apiKey() != null) {
            credentials.apiKey(route.apiKey().reveal());
        }
        return GenerateOptions.mergeOptions(credentials.build(), options);
    }
}
