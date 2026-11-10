package cn.refinex.meridian.runtime.web;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.persistence.idempotency.Idempotency;
import cn.refinex.meridian.persistence.idempotency.IdempotentResult;
import cn.refinex.meridian.persistence.idempotency.StoredResponse;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 公开 API 的 POST 处理器执行业务的统一入口：带 {@code Idempotency-Key} 时经 {@code persistence.idempotency} 的通用实现执行，
 * 24 小时内同键同请求体返回首次结果，同键不同请求体返回 409（架构设计「API 设计」中的「约定」，ADR-0020）。
 *
 * <p>请求指纹是方法、路径与请求 DTO 的规范化 JSON（映射的键排序）的摘要，所以只是空白或字段顺序不同的请求体视为相同。
 * 幂等作用域是 API Key（{@code PlatformContext.apiKeyId}）：不同调用方的同名键互不影响。业务以异常结束时键被释放，可以重试。
 * 重放的响应带 {@code Idempotent-Replayed: true}。
 *
 * <p>业务在虚拟线程上执行（{@code Blocking}），可以直接调用阻塞的用例。线程安全：无状态。
 */
@Component
public class IdempotentRequests {

    /** 重放响应的响应头。 */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /** 计算指纹用的映射器：映射按键排序，同一请求总得到相同的字节。 */
    private static final JsonMapper FINGERPRINT = JsonMappers.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final Idempotency idempotency;

    /**
     * 创建执行入口。
     *
     * @param idempotency 幂等键的通用实现
     */
    public IdempotentRequests(Idempotency idempotency) {
        this.idempotency = idempotency;
    }

    /**
     * 执行一个 POST 请求的业务。
     *
     * @param exchange 当前请求
     * @param idempotencyKey {@code Idempotency-Key}；可为空，表示不做幂等
     * @param request 请求体 DTO；可为空，表示没有请求体
     * @param status 成功时的状态码
     * @param responseType 响应体 DTO 的类型，重放时据此解析保存的响应
     * @param action 业务，返回响应体 DTO；在虚拟线程上执行
     * @param <T> 响应体类型
     * @return 响应
     */
    public <T> Mono<ResponseEntity<T>> execute(
            ServerWebExchange exchange,
            String idempotencyKey,
            Object request,
            HttpStatus status,
            Class<T> responseType,
            Supplier<T> action) {
        if (idempotencyKey == null) {
            return Blocking.call(action::get)
                    .map(body -> ResponseEntity.status(status).body(body));
        }
        String fingerprint = Idempotency.fingerprint(
                exchange.getRequest().getMethod().name(),
                exchange.getRequest().getPath().pathWithinApplication().value(),
                request == null ? new byte[0] : FINGERPRINT.writeValueAsBytes(request));
        return Blocking.call(() -> idempotency.execute(
                        PlatformContexts.current().actor(),
                        idempotencyKey,
                        fingerprint,
                        () -> new StoredResponse(
                                status.value(), JsonMappers.lenient().writeValueAsString(action.get()))))
                .map(result -> toResponse(result, responseType));
    }

    private static <T> ResponseEntity<T> toResponse(IdempotentResult result, Class<T> responseType) {
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(result.response().status());
        if (result.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(JsonMappers.lenient().readValue(result.response().body(), responseType));
    }
}
