package cn.refinex.meridian.engine.agentscope.model;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.engine.spi.error.ProviderRejectedException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.gemini.formatter.GeminiChatFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Gemini API 的平台适配器（ADR-0046）：消息、工具、选项的转换与响应的解析用 2.0.4 的 {@link GeminiChatFormatter}，SDK 客户端由平台
 * 构造：
 *
 * <ul>
 *   <li>{@code google-genai} 的 {@link Client} 只能在构造时给密钥，并且在每个请求上用它覆盖请求级的 {@code x-goog-api-key}，所以按凭据的
 *       SHA-256 指纹缓存 SDK 客户端，写入 5 分钟后过期并关闭（与 runtime 凭据明文的缓存上限一致）；本适配器实例本身不含凭据。
 *   <li>{@code HttpRetryOptions.attempts(1)}（重试只在 {@code RoutedModel}），整个调用的超时 10 分钟。
 *   <li>阻塞的发起与流式迭代都在 {@link Blocking#scheduler()}（虚拟线程）上，不用 {@code boundedElastic}（P7）。
 *   <li>{@link ApiException} 转成带状态码的 {@link HttpTransportException}（SDK 不带响应头，没有 {@code Retry-After}，按指数退避），
 *       {@link GenAiIOException} 转成可重试的传输异常。
 *   <li>2.0.4 的 {@code GeminiMediaConverter} 对 URL 来源的图片、音视频与数据块在本进程内下载或读本机文件：遇到它们以终止的
 *       {@link ProviderRejectedException} 结束本次调用（D52）。
 * </ul>
 *
 * <p>线程安全：可被并发调用。
 */
final class GeminiContentModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(GeminiContentModel.class);

    /** SDK 客户端（含凭据）的存活时间：与 runtime 凭据明文的缓存上限一致。 */
    static final Duration CLIENT_TTL = Duration.ofMinutes(5);

    /** 整个调用（含流式输出）的上限，单位毫秒。 */
    private static final int CALL_TIMEOUT_MILLIS = (int) Duration.ofMinutes(10).toMillis();

    private final String baseUrl;
    private final String upstreamModel;
    private final Clock clock;
    private final GeminiChatFormatter formatter = new GeminiChatFormatter();
    private final Cache<String, Client> clients = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(CLIENT_TTL)
            .scheduler(Scheduler.systemScheduler())
            .removalListener(GeminiContentModel::closeClient)
            .build();

    /**
     * 创建适配器。
     *
     * @param baseUrl 端点；为空表示官方端点
     * @param upstreamModel 供应商侧的模型名
     * @param clock 时钟
     */
    GeminiContentModel(String baseUrl, String upstreamModel, Clock clock) {
        this.baseUrl = baseUrl;
        this.upstreamModel = upstreamModel;
        this.clock = clock;
    }

    @Override
    public String getModelName() {
        return upstreamModel;
    }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.<ChatResponse>defer(() -> {
                    GenerateOptions effective =
                            options == null ? GenerateOptions.builder().build() : options;
                    if (effective.getApiKey() == null) {
                        return Flux.error(new ProviderRejectedException("Gemini 供应商账号没有配置凭据", null));
                    }
                    if (hasUrlMedia(messages)) {
                        return Flux.error(new ProviderRejectedException("Gemini 模型不接受以 URL 引用的图片或文件，请改用平台文件", null));
                    }
                    Client client =
                            clients.get(fingerprint(effective.getApiKey()), key -> build(effective.getApiKey()));
                    GenerateContentConfig.Builder config = GenerateContentConfig.builder();
                    List<Content> contents = formatter.format(messages);
                    if (tools != null && !tools.isEmpty()) {
                        formatter.applyTools(config, tools);
                        if (effective.getToolChoice() != null) {
                            formatter.applyToolChoice(config, effective.getToolChoice());
                        }
                    }
                    formatter.applyOptions(
                            config, effective, GenerateOptions.builder().build());
                    Instant start = clock.instant();
                    ResponseStream<GenerateContentResponse> stream;
                    try {
                        stream = client.models.generateContentStream(upstreamModel, contents, config.build());
                    } catch (RuntimeException e) {
                        return Flux.error(translate(e));
                    }
                    return Flux.fromIterable(stream)
                            .map(response -> formatter.parseResponse(response, start))
                            .onErrorMap(GeminiContentModel::translate)
                            .doFinally(signal -> closeStream(stream));
                })
                .subscribeOn(Blocking.scheduler());
    }

    private Client build(String apiKey) {
        HttpOptions.Builder http = HttpOptions.builder()
                .timeout(CALL_TIMEOUT_MILLIS)
                .retryOptions(HttpRetryOptions.builder().attempts(1).build());
        if (baseUrl != null) {
            http.baseUrl(baseUrl);
        }
        return Client.builder()
                .apiKey(apiKey)
                .vertexAI(false)
                .httpOptions(http.build())
                .build();
    }

    /** SDK 的异常转成平台能分类的传输异常；消息里只有状态码。 */
    private static Throwable translate(Throwable error) {
        if (error instanceof ApiException api) {
            return new HttpTransportException("Gemini 返回 HTTP " + api.code(), api.code(), null, api);
        }
        if (error instanceof GenAiIOException io) {
            return new HttpTransportException("Gemini 请求失败", io);
        }
        return error;
    }

    /** 消息（含工具结果）中有 URL 来源的媒体块。 */
    static boolean hasUrlMedia(List<Msg> messages) {
        if (messages == null) {
            return false;
        }
        return messages.stream().flatMap(m -> m.getContent().stream()).anyMatch(GeminiContentModel::urlMedia);
    }

    private static boolean urlMedia(ContentBlock block) {
        return switch (block) {
            case ImageBlock image -> image.getSource() instanceof URLSource;
            case AudioBlock audio -> audio.getSource() instanceof URLSource;
            case VideoBlock video -> video.getSource() instanceof URLSource;
            case DataBlock data -> data.getSource() instanceof URLSource;
            case ToolResultBlock result ->
                result.getOutput() != null && result.getOutput().stream().anyMatch(GeminiContentModel::urlMedia);
            default -> false;
        };
    }

    private static String fingerprint(String apiKey) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void closeClient(String key, Client client, RemovalCause cause) {
        if (client != null) {
            client.close();
        }
    }

    private static void closeStream(ResponseStream<GenerateContentResponse> stream) {
        try {
            stream.close();
        } catch (RuntimeException e) {
            log.debug("关闭 Gemini 的流式响应失败", e);
        }
    }
}
