package cn.refinex.meridian.runtime.modelrouting.api;

import cn.refinex.meridian.api.model.ChatCompletionChunk;
import cn.refinex.meridian.api.model.ChatCompletionChunkChoice;
import cn.refinex.meridian.api.model.CreateChatCompletionRequest;
import cn.refinex.meridian.api.model.CreateEmbeddingRequest;
import cn.refinex.meridian.api.model.CreateResponseRequest;
import cn.refinex.meridian.api.model.Embedding;
import cn.refinex.meridian.api.model.EmbeddingList;
import cn.refinex.meridian.api.model.EmbeddingUsage;
import cn.refinex.meridian.api.model.ModelResponse;
import cn.refinex.meridian.api.model.ResponseStreamEvent;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatChunk;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatRequest;
import cn.refinex.meridian.runtime.modelrouting.api.ChatCompletionMapper.Aggregate;
import cn.refinex.meridian.runtime.modelrouting.api.ChatCompletionMapper.ChunkWriter;
import cn.refinex.meridian.runtime.modelrouting.api.ChatCompletionMapper.Completion;
import cn.refinex.meridian.runtime.modelrouting.app.VectorModelService;
import cn.refinex.meridian.runtime.openapi.ModelsApi;
import cn.refinex.meridian.runtime.web.IdempotentRequests;
import cn.refinex.meridian.security.apikey.ApiKeyAuthorization;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模型直通接口（OpenAI 兼容）：{@code POST /v1/chat/completions}、{@code POST /v1/responses}（子集，ADR-0050）与
 * {@code POST /v1/embeddings}，作用域 {@code models:invoke}。
 *
 * <p>对话补全经 {@link ModelInvoker}（内部是 {@code RoutedModel}），向量经 {@link VectorModelService}，与智能体的模型调用共用限流、
 * 预扣与计量。直通接口的 {@code PlatformContext} 只有租户、项目与 API Key，预算只计这三级。
 *
 * <p>流式对话补全：先等到第一个增量（或错误）再提交响应，首包之前的错误因此以对应状态码的 problem+json 返回；之后的错误以一条
 * {@code {"error": {...}}} 结束流（ADR-0033）；Responses 以一条 {@code response.failed} 结束流。
 */
@RestController
public class ModelsApiController implements ModelsApi {

    private static final Logger log = LoggerFactory.getLogger(ModelsApiController.class);

    /** SSE 的结束标记（OpenAI 兼容）。 */
    static final String DONE = "[DONE]";

    /** 流式块的编码：没有值的字段不输出，与 OpenAI 的增量格式一致；{@code finish_reason} 例外，OpenAI 在每个增量里都给出（可为 null）。 */
    private static final JsonMapper CHUNK_JSON = JsonMappers.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .addMixIn(ChatCompletionChunkChoice.class, FinishReasonAlways.class)
            .build();

    /** Responses 的流式事件：事件本身没有值的字段不出现，内嵌的响应对象照常输出（必填的可空字段为 null）。 */
    private static final JsonMapper EVENT_JSON = JsonMappers.builder()
            .addMixIn(ResponseStreamEvent.class, OmitNulls.class)
            .build();

    private final ModelInvoker invoker;
    private final VectorModelService vectors;
    private final IdempotentRequests idempotent;
    private final Clock clock;

    /**
     * 创建处理器。
     *
     * @param invoker 直通的聊天调用
     * @param vectors 向量用例
     * @param idempotent POST 的幂等执行
     * @param clock 时钟，补全的创建时间
     */
    public ModelsApiController(
            ModelInvoker invoker, VectorModelService vectors, IdempotentRequests idempotent, Clock clock) {
        this.invoker = invoker;
        this.vectors = vectors;
        this.idempotent = idempotent;
        this.clock = clock;
    }

    @Override
    public Mono<ResponseEntity<Object>> createChatCompletion(
            Mono<CreateChatCompletionRequest> createChatCompletionRequest,
            String idempotencyKey,
            ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.MODELS_INVOKE);
        return createChatCompletionRequest.flatMap(request -> {
            ChatRequest chat = ChatCompletionMapper.toChatRequest(request);
            Completion completion =
                    new Completion("chatcmpl-" + Ids.newUlid(), clock.instant().getEpochSecond(), request.getModel());
            if (Boolean.TRUE.equals(request.getStream())) {
                if (idempotencyKey != null) {
                    throw new ValidationException(
                            CommonErrorCode.VALIDATION_FAILED,
                            "流式请求不支持 Idempotency-Key",
                            List.of(new FieldViolation("Idempotency-Key", "stream 为 true 时不能带幂等键")));
                }
                boolean includeUsage = request.getStreamOptions() != null
                        && Boolean.TRUE.equals(request.getStreamOptions().getIncludeUsage());
                return stream(chat, completion, includeUsage);
            }
            if (idempotencyKey == null) {
                return aggregate(chat)
                        .map(aggregate -> ResponseEntity.ok().body((Object) aggregate.toCompletion(completion)));
            }
            return idempotent
                    .execute(
                            exchange,
                            idempotencyKey,
                            request,
                            HttpStatus.OK,
                            cn.refinex.meridian.api.model.ChatCompletion.class,
                            // 幂等执行在虚拟线程上，PlatformContext 由 Blocking 以 ScopedValue 传入，这里写回 Reactor Context
                            () -> aggregate(chat)
                                    .contextWrite(PlatformContexts.write(PlatformContexts.current()))
                                    .map(aggregate -> aggregate.toCompletion(completion))
                                    .block())
                    .map(response -> ResponseEntity.status(response.getStatusCode())
                            .headers(response.getHeaders())
                            .body((Object) response.getBody()));
        });
    }

    @Override
    public Mono<ResponseEntity<Object>> createResponse(
            Mono<CreateResponseRequest> createResponseRequest, String idempotencyKey, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.MODELS_INVOKE);
        return createResponseRequest.flatMap(request -> {
            ChatRequest chat = ResponsesMapper.toChatRequest(request);
            ResponsesMapper.Identity identity = new ResponsesMapper.Identity(
                    "resp_" + Ids.newUlid(),
                    "msg_" + Ids.newUlid(),
                    clock.instant().getEpochSecond(),
                    request.getModel());
            if (Boolean.TRUE.equals(request.getStream())) {
                if (idempotencyKey != null) {
                    throw new ValidationException(
                            CommonErrorCode.VALIDATION_FAILED,
                            "流式请求不支持 Idempotency-Key",
                            List.of(new FieldViolation("Idempotency-Key", "stream 为 true 时不能带幂等键")));
                }
                return firstSignal(chat)
                        .map(chunks -> eventStream(responseEvents(chunks, new ResponsesMapper.StreamWriter(identity))));
            }
            if (idempotencyKey == null) {
                return aggregate(chat)
                        .map(aggregate ->
                                ResponseEntity.ok().body((Object) ResponsesMapper.completed(identity, aggregate)));
            }
            return idempotent
                    .execute(
                            exchange,
                            idempotencyKey,
                            request,
                            HttpStatus.OK,
                            ModelResponse.class,
                            () -> aggregate(chat)
                                    .contextWrite(PlatformContexts.write(PlatformContexts.current()))
                                    .map(aggregate -> ResponsesMapper.completed(identity, aggregate))
                                    .block())
                    .map(response -> ResponseEntity.status(response.getStatusCode())
                            .headers(response.getHeaders())
                            .body((Object) response.getBody()));
        });
    }

    @Override
    public Mono<ResponseEntity<EmbeddingList>> createEmbeddings(
            Mono<CreateEmbeddingRequest> createEmbeddingRequest, String idempotencyKey, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.MODELS_INVOKE);
        return createEmbeddingRequest.flatMap(request -> {
            List<String> inputs = inputs(request.getInput());
            boolean base64 = request.getEncodingFormat() == CreateEmbeddingRequest.EncodingFormatEnum.BASE64;
            if (idempotencyKey == null) {
                return vectors.embed(request.getModel(), inputs, request.getDimensions())
                        .map(result -> ResponseEntity.ok(toEmbeddingList(request.getModel(), result, base64)));
            }
            return idempotent.execute(
                    exchange,
                    idempotencyKey,
                    request,
                    HttpStatus.OK,
                    EmbeddingList.class,
                    () -> vectors.embed(request.getModel(), inputs, request.getDimensions())
                            .contextWrite(PlatformContexts.write(PlatformContexts.current()))
                            .map(result -> toEmbeddingList(request.getModel(), result, base64))
                            .block());
        });
    }

    private Mono<Aggregate> aggregate(ChatRequest chat) {
        return invoker.stream(chat).reduce(new Aggregate(), Aggregate::add);
    }

    /** 流式响应：等到第一个信号再提交，首包之前的错误走 problem+json。 */
    private Mono<ResponseEntity<Object>> stream(ChatRequest chat, Completion completion, boolean includeUsage) {
        return firstSignal(chat).map(chunks -> eventStream(frames(chunks, completion, includeUsage)));
    }

    /** 等到供应商的第一个信号：是错误时以该错误结束（调用方返回 problem+json），否则给出完整的块流。 */
    private Mono<Flux<ChatChunk>> firstSignal(ChatRequest chat) {
        return invoker.stream(chat)
                .switchOnFirst(
                        (first, chunks) -> first.isOnError()
                                ? Flux.<Flux<ChatChunk>>error(first.getThrowable())
                                : Flux.just(chunks),
                        false)
                // single 等外层（Flux.just）自然完成，不发取消；next 会取消外层，switchOnFirst 随之取消供应商的流
                .single();
    }

    private static ResponseEntity<Object> eventStream(Flux<ServerSentEvent<String>> frames) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body((Object) frames);
    }

    /** Responses 的事件序列：开头四个事件、文本增量、结尾四个事件；首包之后出错时以 {@code response.failed} 结束。 */
    private static Flux<ServerSentEvent<String>> responseEvents(
            Flux<ChatChunk> chunks, ResponsesMapper.StreamWriter writer) {
        return Flux.defer(() -> {
                    Flux<ResponseStreamEvent> deltas = chunks.handle((chunk, sink) -> {
                        ResponseStreamEvent delta = writer.delta(chunk);
                        if (delta != null) {
                            sink.next(delta);
                        }
                    });
                    return Flux.fromIterable(writer.start())
                            .concatWith(deltas)
                            .concatWith(Flux.defer(() -> Flux.fromIterable(writer.finish())))
                            .onErrorResume(error -> Flux.just(writer.failed(error)));
                })
                .map(event -> ServerSentEvent.<String>builder()
                        .event(event.getType().getValue())
                        .data(EVENT_JSON.writeValueAsString(event))
                        .build());
    }

    private static Flux<ServerSentEvent<String>> frames(
            Flux<ChatChunk> chunks, Completion completion, boolean includeUsage) {
        return Flux.defer(() -> {
            ChunkWriter writer = new ChunkWriter(completion);
            Flux<ServerSentEvent<String>> deltas = chunks.<ChatCompletionChunk>handle((chunk, sink) -> {
                        ChatCompletionChunk delta = writer.delta(chunk);
                        if (delta != null) {
                            sink.next(delta);
                        }
                    })
                    .map(ModelsApiController::frame);
            Flux<ServerSentEvent<String>> tail = Flux.defer(() -> {
                ChatCompletionChunk usage = includeUsage ? writer.usageChunk() : null;
                return usage == null ? Flux.just(data(DONE)) : Flux.just(frame(usage), data(DONE));
            });
            return deltas.concatWith(tail)
                    .onErrorResume(error ->
                            Flux.just(data(CHUNK_JSON.writeValueAsString(ChatCompletionMapper.errorFrame(error)))));
        });
    }

    private static ServerSentEvent<String> frame(ChatCompletionChunk chunk) {
        return data(CHUNK_JSON.writeValueAsString(chunk));
    }

    private static ServerSentEvent<String> data(String data) {
        return ServerSentEvent.<String>builder().data(data).build();
    }

    /** {@code input} 是一段文本或文本数组（规范的 {@code EmbeddingInput}，生成的 DTO 中是 Object）。 */
    static List<String> inputs(Object input) {
        if (input instanceof String text && !text.isEmpty()) {
            return List.of(text);
        }
        if (input instanceof List<?> list
                && !list.isEmpty()
                && list.size() <= 256
                && list.stream().allMatch(item -> item instanceof String text && !text.isEmpty())) {
            return list.stream().map(String.class::cast).toList();
        }
        throw new ValidationException(
                CommonErrorCode.VALIDATION_FAILED,
                "input 不合法",
                List.of(new FieldViolation("input", "必须是非空文本，或 1 至 256 条非空文本的数组")));
    }

    private static EmbeddingList toEmbeddingList(String model, VectorModelService.Embeddings result, boolean base64) {
        List<Embedding> data = new ArrayList<>(result.vectors().size());
        for (int i = 0; i < result.vectors().size(); i++) {
            float[] vector = result.vectors().get(i);
            data.add(new Embedding(Embedding.ObjectEnum.EMBEDDING, i, base64 ? base64(vector) : floats(vector)));
        }
        return new EmbeddingList(
                EmbeddingList.ObjectEnum.LIST,
                data,
                model,
                new EmbeddingUsage(result.inputTokens(), result.inputTokens()));
    }

    private static List<Float> floats(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    /** 小端 float32 数组的 Base64（OpenAI 的 {@code encoding_format=base64}）。 */
    static String base64(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    /** 没有值的字段不输出（只作用于被混入的类型本身）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private abstract static class OmitNulls {}

    /** {@link ChatCompletionChunkChoice} 的 mixin：{@code finish_reason} 为 null 时也输出。 */
    private abstract static class FinishReasonAlways {

        @JsonInclude(JsonInclude.Include.ALWAYS)
        @JsonProperty("finish_reason")
        abstract String getFinishReason();
    }
}
