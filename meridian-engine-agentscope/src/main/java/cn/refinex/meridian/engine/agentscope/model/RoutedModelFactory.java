package cn.refinex.meridian.engine.agentscope.model;

import cn.refinex.meridian.engine.agentscope.assembly.ModelFactory;
import cn.refinex.meridian.engine.spi.port.ModelGateway;
import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelDescriptor;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import io.agentscope.core.model.Model;
import java.time.Duration;
import java.util.Objects;

/**
 * 生产用的模型工厂：每个智能体实例一个 {@link RoutedModel}，不经 AgentScope 的 {@code ModelRegistry}（架构设计「引擎装配」）。
 *
 * <p>装配时经 {@link ModelGateway#describe} 读取主模型的上下文窗口并设置到模型上；凭据与路由在每次调用时解析。
 * 装配在虚拟线程上执行（{@code AgentInstanceCache} 经 {@code Blocking} 调用），这里可以阻塞等待目录读取。
 *
 * <p>线程安全：无可变状态。
 */
public final class RoutedModelFactory implements ModelFactory {

    /** 读取模型目录的等待上限。 */
    static final Duration DESCRIBE_TIMEOUT = Duration.ofSeconds(10);

    private final ModelGateway gateway;
    private final ProviderClients clients;
    private final RoutedModel.RetryPolicy retryPolicy;

    /**
     * 创建工厂。
     *
     * @param gateway 模型调用的平台侧服务
     * @param clients 底层客户端的登记与缓存
     * @param retryPolicy 首包之前的重试策略
     */
    public RoutedModelFactory(ModelGateway gateway, ProviderClients clients, RoutedModel.RetryPolicy retryPolicy) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    @Override
    public Model create(String agentVersionId, ModelSpec model) {
        ModelDescriptor descriptor = gateway.describe(model.modelId()).block(DESCRIBE_TIMEOUT);
        Objects.requireNonNull(descriptor, "模型目录没有返回模型信息：" + model.modelId());
        return new RoutedModel(gateway, clients, retryPolicy, model, agentVersionId, descriptor.contextWindow());
    }
}
