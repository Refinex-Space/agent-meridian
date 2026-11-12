package cn.refinex.meridian.engine.agentscope.model;

import cn.refinex.meridian.common.concurrent.Blocking;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.Timeout;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicChatFormatter;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicResponseParser;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Anthropic Messages API 的平台适配器（ADR-0046）：消息、系统提示词、工具、选项的转换与流式事件的解析用 2.0.4 的
 * {@link AnthropicChatFormatter}、{@link AnthropicResponseParser}，SDK 客户端由平台构造：
 *
 * <ul>
 *   <li>不带凭据，{@code maxRetries(0)}（重试只在 {@code RoutedModel}），超时与 OpenAI 兼容的传输一致；凭据取本次调用选项的
 *       {@code apiKey}，作为本次请求的 {@code x-api-key} 头。
 *   <li>阻塞的发起与流式迭代都在 {@link Blocking#scheduler()}（虚拟线程）上，不用有线程上限的 {@code boundedElastic}（P7）。
 *   <li>SDK 的状态码异常转成不带响应体的 {@link HttpTransportException}，{@code retry-after} 记到本次尝试的
 *       {@link ProviderResponseObserver}；I/O 异常转成可重试的传输异常。分类由 {@link ProviderErrors} 完成。
 * </ul>
 *
 * <p>图片：入口只接受 http(s) 的 {@code image.url}（ADR-0045），转换器把它作为 URL 来源交给 Anthropic 下载，不读本机文件。
 *
 * <p>线程安全：可被并发调用；formatter 在 {@code applyOptions} 与 {@code applyTools} 之间用线程局部变量传递选项，两者在同一线程上调用。
 */
final class AnthropicMessagesModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessagesModel.class);

    /** 请求没有给出最大输出时的默认值（{@code RoutedModel} 总会给出）。 */
    private static final long DEFAULT_MAX_TOKENS = 4096;

    /** 连接超时，与 {@link ProviderHttpTransport#DEFAULT_CONNECT_TIMEOUT} 相同。 */
    private static final Duration CONNECT_TIMEOUT = ProviderHttpTransport.DEFAULT_CONNECT_TIMEOUT;

    /** 读超时：首包与流式数据的间隔都不超过它，与 {@link ProviderHttpTransport#DEFAULT_FIRST_CHUNK_TIMEOUT} 相同。 */
    private static final Duration READ_TIMEOUT = ProviderHttpTransport.DEFAULT_FIRST_CHUNK_TIMEOUT;

    /** 整个请求（含流式输出）的上限。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private final AnthropicClient client;
    private final String upstreamModel;
    private final Clock clock;
    private final AnthropicChatFormatter formatter = new AnthropicChatFormatter();

    /**
     * 创建适配器。
     *
     * @param baseUrl 端点；为空表示官方端点
     * @param upstreamModel 供应商侧的模型名
     * @param clock 时钟
     */
    AnthropicMessagesModel(String baseUrl, String upstreamModel, Clock clock) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .maxRetries(0)
                .timeout(Timeout.builder()
                        .connect(CONNECT_TIMEOUT)
                        .read(READ_TIMEOUT)
                        .request(REQUEST_TIMEOUT)
                        .build());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        this.client = builder.build();
        this.upstreamModel = upstreamModel;
        this.clock = clock;
    }

    @Override
    public String getModelName() {
        return upstreamModel;
    }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.<ChatResponse>deferContextual(context -> {
                    ProviderResponseObserver observer = context.getOrDefault(ProviderResponseObserver.class, null);
                    MessageCreateParams params = params(messages, tools, options);
                    StreamResponse<RawMessageStreamEvent> response;
                    try {
                        response = client.messages().createStreaming(params);
                    } catch (RuntimeException e) {
                        return Flux.error(translate(e, observer));
                    }
                    return AnthropicResponseParser.parseStreamEvents(
                                    Flux.fromStream(response.stream()), clock.instant())
                            .onErrorMap(e -> translate(e, observer))
                            .doFinally(signal -> close(response));
                })
                .subscribeOn(Blocking.scheduler());
    }

    /** 与 2.0.4 的 {@code AnthropicChatModel.doStream} 相同的请求组装，另加本次请求的凭据头。 */
    private MessageCreateParams params(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        GenerateOptions effective = options == null ? GenerateOptions.builder().build() : options;
        MessageCreateParams.Builder builder =
                MessageCreateParams.builder().model(upstreamModel).maxTokens(DEFAULT_MAX_TOKENS);
        boolean cacheControl = Boolean.TRUE.equals(effective.getCacheControl());
        formatter.applySystemMessage(builder, messages, cacheControl);
        List<Msg> conversation = messages;
        if (messages != null && !messages.isEmpty() && messages.getFirst().getRole() == MsgRole.SYSTEM) {
            conversation = messages.subList(1, messages.size());
        }
        List<MessageParam> formatted = formatter.format(conversation);
        if (cacheControl) {
            formatted = formatter.applyCacheControl(formatted);
        }
        formatted.forEach(builder::addMessage);
        formatter.applyOptions(builder, effective, GenerateOptions.builder().build());
        if (tools != null && !tools.isEmpty()) {
            formatter.applyTools(builder, tools);
        }
        if (effective.getApiKey() != null) {
            builder.putAdditionalHeader("x-api-key", effective.getApiKey());
        }
        return builder.build();
    }

    /** SDK 的异常转成平台能分类的传输异常；消息里只有状态码，不带响应体与请求头。 */
    private Throwable translate(Throwable error, ProviderResponseObserver observer) {
        if (error instanceof AnthropicServiceException service) {
            int status = service.statusCode();
            if (observer != null) {
                observer.record(status, retryAfter(service), clock);
            }
            return new HttpTransportException("Anthropic 返回 HTTP " + status, status, null, service);
        }
        if (error instanceof AnthropicIoException io) {
            return new HttpTransportException("Anthropic 请求失败", io);
        }
        return error;
    }

    private static String retryAfter(AnthropicServiceException service) {
        List<String> values = service.headers().values("retry-after");
        if (values.isEmpty()) {
            values = service.headers().values("Retry-After");
        }
        return values.isEmpty() ? null : values.getFirst();
    }

    private static void close(StreamResponse<RawMessageStreamEvent> response) {
        try {
            response.close();
        } catch (RuntimeException e) {
            log.debug("关闭 Anthropic 的流式响应失败", e);
        }
    }
}
