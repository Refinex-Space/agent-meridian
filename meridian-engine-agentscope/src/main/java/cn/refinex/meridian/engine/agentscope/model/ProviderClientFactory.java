package cn.refinex.meridian.engine.agentscope.model;

import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelRoute;
import cn.refinex.meridian.engine.spi.port.ModelGateway.Provider;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;

/**
 * 一种供应商类型的底层客户端构造方式：用 AgentScope 对应的模型类构造 {@link Model}，见架构设计「模型调用」第 1 步。
 *
 * <p>底层客户端按"供应商账号加模型"缓存（{@link ProviderClients}），所以 {@link #create} 构造的实例<b>不含凭据</b>；
 * 凭据与端点在每次调用时经 {@link #callOptions} 放进本次调用的选项，用完即弃（架构原则第 6 条）。
 *
 * <p>实现：OpenAI 兼容（2.0.4 的 {@code OpenAIChatModel}）由 {@link OpenAiCompatibleClientFactory}；Anthropic、Gemini 由
 * {@link AnthropicClientFactory}、{@link GeminiClientFactory} 构造平台适配器，复用 2.0.4 的 formatter 与响应解析器（ADR-0046：2.0.4
 * 的这两个模型类只能在构造时给凭据、SDK 自带重试）。DashScope 原生与 Ollama 没有实现。
 */
public interface ProviderClientFactory {

    /**
     * 本工厂负责的供应商类型。
     *
     * @return 供应商类型
     */
    Provider provider();

    /**
     * 构造底层客户端，不含凭据。
     *
     * @param route 路由信息
     * @return 底层模型
     */
    Model create(ModelRoute route);

    /**
     * 本次调用的选项：在平台算好的选项上补上凭据、端点与供应商侧的模型名。
     *
     * @param route 路由信息，含凭据明文
     * @param options 平台算好的选项（已合并调用方的选项）
     * @return 本次调用使用的选项
     */
    GenerateOptions callOptions(ModelRoute route, GenerateOptions options);
}
