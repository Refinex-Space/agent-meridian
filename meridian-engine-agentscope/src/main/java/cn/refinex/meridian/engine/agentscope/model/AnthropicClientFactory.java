package cn.refinex.meridian.engine.agentscope.model;

import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelRoute;
import cn.refinex.meridian.engine.spi.port.ModelGateway.Provider;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.time.Clock;
import java.util.Objects;

/**
 * Anthropic 的底层客户端（ADR-0046）：平台适配器 {@link AnthropicMessagesModel}，复用 2.0.4 的 {@code AnthropicChatFormatter} 与
 * {@code AnthropicResponseParser}，不用 2.0.4 的 {@code AnthropicChatModel}（凭据只能在构造时给出、SDK 自带 2 次重试关不掉、阻塞流在
 * {@code boundedElastic} 上迭代）。
 *
 * <p>适配器按"供应商账号加模型"缓存，不含凭据；凭据在每次调用的选项中给出（{@link #callOptions}），适配器把它作为本次请求的
 * {@code x-api-key} 头。线程安全：无状态。
 */
public final class AnthropicClientFactory implements ProviderClientFactory {

    private final Clock clock;

    /**
     * 创建工厂。
     *
     * @param clock 时钟，解析 HTTP 日期格式的 {@code retry-after} 时使用
     */
    public AnthropicClientFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Provider provider() {
        return Provider.ANTHROPIC;
    }

    @Override
    public Model create(ModelRoute route) {
        return new AnthropicMessagesModel(route.baseUrl(), route.upstreamModel(), clock);
    }

    @Override
    public GenerateOptions callOptions(ModelRoute route, GenerateOptions options) {
        if (route.apiKey() == null) {
            return options;
        }
        return GenerateOptions.mergeOptions(
                GenerateOptions.builder().apiKey(route.apiKey().reveal()).build(), options);
    }
}
