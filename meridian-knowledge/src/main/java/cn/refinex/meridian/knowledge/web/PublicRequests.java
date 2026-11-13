package cn.refinex.meridian.knowledge.web;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.persistence.idempotency.Idempotency;
import cn.refinex.meridian.persistence.idempotency.IdempotentResult;
import cn.refinex.meridian.persistence.idempotency.StoredResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 公开 API 的 POST 处理器执行业务的统一入口（架构设计「约定」：所有 POST 支持 {@code Idempotency-Key}，复用
 * {@code persistence.idempotency}），与 runtime 的 {@code IdempotentRequests} 相同的语义：同键同请求 24 小时内返回首次结果并带
 * {@code Idempotent-Replayed: true}，同键不同请求 409。作用域是请求主体（{@code PlatformContext.idempotencyPrincipal()}：API Key，或控制台用户加项目），
 * {@code PlatformContext} 已由 {@code ApiKeyFilter} 绑定。
 */
@Component
public class PublicRequests {

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
    public PublicRequests(Idempotency idempotency) {
        this.idempotency = idempotency;
    }

    /**
     * 执行一个 POST。
     *
     * @param request 当前请求
     * @param idempotencyKey {@code Idempotency-Key}；可为空，表示不做幂等
     * @param fingerprintBody 参与指纹的请求内容（请求体 DTO，或上传文件的名字与摘要）；可为空
     * @param status 成功时的状态码
     * @param responseType 响应体 DTO 的类型，重放时据此解析保存的响应
     * @param action 业务，返回响应体 DTO
     * @param <T> 响应体类型
     * @return 响应
     */
    public <T> ResponseEntity<T> execute(
            HttpServletRequest request,
            String idempotencyKey,
            Object fingerprintBody,
            HttpStatus status,
            Class<T> responseType,
            Supplier<T> action) {
        if (idempotencyKey == null) {
            return ResponseEntity.status(status).body(action.get());
        }
        String fingerprint = Idempotency.fingerprint(
                request.getMethod(),
                request.getRequestURI(),
                fingerprintBody == null ? new byte[0] : FINGERPRINT.writeValueAsBytes(fingerprintBody));
        IdempotentResult result = idempotency.execute(
                PlatformContexts.current().idempotencyPrincipal(),
                idempotencyKey,
                fingerprint,
                () -> new StoredResponse(status.value(), JsonMappers.lenient().writeValueAsString(action.get())));
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(result.response().status());
        if (result.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(JsonMappers.lenient().readValue(result.response().body(), responseType));
    }
}
