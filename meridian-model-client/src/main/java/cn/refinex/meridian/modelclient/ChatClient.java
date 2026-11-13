package cn.refinex.meridian.modelclient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import org.springframework.web.client.RestClient;

/**
 * 调用 runtime 内部接口 {@code POST /internal/v1/chat/completions} 的客户端：检索前的查询改写（架构设计「检索流程」第 1 步）。
 * 非流式、只有文本；限流、预算与计量在 runtime。传入的 {@link RestClient} 与 {@link EmbeddingClient} 相同。
 *
 * <p>线程安全：无状态，可共享。
 */
public final class ChatClient {

    static final String PATH = "/internal/v1/chat/completions";

    private final RestClient runtime;

    /**
     * 创建客户端。
     *
     * @param runtime 指向 runtime、带服务 JWT 的客户端
     */
    public ChatClient(RestClient runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * 对话补全。
     *
     * @param request 请求
     * @return 模型输出的文本与用量
     * @throws ModelClientException runtime 拒绝请求或不可用时
     */
    public ChatResult complete(ChatRequest request) {
        WireResponse response = RuntimeCalls.post(runtime, PATH, WireRequest.of(request), WireResponse.class);
        long prompt = response.usage() == null ? 0 : response.usage().promptTokens();
        long completion = response.usage() == null ? 0 : response.usage().completionTokens();
        return new ChatResult(response.content() == null ? "" : response.content(), prompt, completion);
    }

    /** 内部接口的请求体（{@code InternalChatRequest}）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WireRequest(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("model") String model,
            @JsonProperty("messages") List<WireMessage> messages,
            @JsonProperty("max_tokens") Integer maxTokens,
            @JsonProperty("temperature") Double temperature) {

        static WireRequest of(ChatRequest request) {
            return new WireRequest(
                    request.tenantId(),
                    request.projectId(),
                    request.model(),
                    request.messages().stream()
                            .map(m -> new WireMessage(m.role().value(), m.content()))
                            .toList(),
                    request.maxTokens(),
                    request.temperature());
        }
    }

    /** 一条消息。 */
    record WireMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content) {}

    /** 内部接口的响应体（{@code InternalChatResponse}）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireResponse(
            @JsonProperty("content") String content,
            @JsonProperty("usage") WireUsage usage) {}

    /** 用量。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireUsage(
            @JsonProperty("prompt_tokens") long promptTokens,
            @JsonProperty("completion_tokens") long completionTokens) {}
}
