package cn.refinex.meridian.controlplane.web;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.persistence.idempotency.Idempotency;
import cn.refinex.meridian.persistence.idempotency.IdempotentResult;
import cn.refinex.meridian.persistence.idempotency.StoredResponse;
import cn.refinex.meridian.security.rbac.AccessContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 管理 API 写操作的执行入口：按 {@code Idempotency-Key} 幂等执行（架构设计「约定」，复用 {@code persistence.idempotency}）。
 *
 * <p>幂等键的作用域是"租户 + 控制台用户"：同一用户在不同租户的同名键互不影响。创建租户时还没有租户，作用域取
 * {@value #PLATFORM_SCOPE}。重放响应带 {@code Idempotent-Replayed: true}；没有键时直接执行。
 */
@Component
public class AdminRequests {

    /** 重放响应的响应头，与公开 API 相同。 */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /** 不针对某个租户的写操作（创建租户）的幂等作用域。 */
    static final String PLATFORM_SCOPE = "platform";

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
    public AdminRequests(Idempotency idempotency) {
        this.idempotency = idempotency;
    }

    /**
     * 执行一个有响应体的写操作。
     *
     * @param request 当前请求
     * @param tenantId 路径中的租户；为空表示不针对某个租户
     * @param idempotencyKey {@code Idempotency-Key}；可为空，表示不做幂等
     * @param body 请求体 DTO；可为空
     * @param status 成功时的状态码
     * @param responseType 响应体 DTO 的类型，重放时据此解析保存的响应
     * @param action 业务，返回响应体 DTO
     * @param <T> 响应体类型
     * @return 响应
     */
    public <T> ResponseEntity<T> write(
            HttpServletRequest request,
            String tenantId,
            String idempotencyKey,
            Object body,
            HttpStatus status,
            Class<T> responseType,
            Supplier<T> action) {
        if (idempotencyKey == null) {
            return ResponseEntity.status(status).body(action.get());
        }
        IdempotentResult result = execute(
                request,
                tenantId,
                idempotencyKey,
                body,
                () -> new StoredResponse(status.value(), JsonMappers.lenient().writeValueAsString(action.get())));
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(result.response().status());
        if (result.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(JsonMappers.lenient().readValue(result.response().body(), responseType));
    }

    /**
     * 执行一个没有响应体的写操作（删除），成功时 204。
     *
     * @param request 当前请求
     * @param tenantId 路径中的租户
     * @param idempotencyKey {@code Idempotency-Key}；可为空，表示不做幂等
     * @param action 业务
     * @return 204 响应
     */
    public ResponseEntity<Void> delete(
            HttpServletRequest request, String tenantId, String idempotencyKey, Runnable action) {
        if (idempotencyKey == null) {
            action.run();
            return ResponseEntity.noContent().build();
        }
        IdempotentResult result = execute(request, tenantId, idempotencyKey, null, () -> {
            action.run();
            return new StoredResponse(HttpStatus.NO_CONTENT.value(), null);
        });
        ResponseEntity.HeadersBuilder<?> builder =
                ResponseEntity.status(result.response().status());
        if (result.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.build();
    }

    private IdempotentResult execute(
            HttpServletRequest request,
            String tenantId,
            String idempotencyKey,
            Object body,
            Supplier<StoredResponse> action) {
        String fingerprint = Idempotency.fingerprint(
                request.getMethod(),
                request.getRequestURI(),
                body == null ? new byte[0] : FINGERPRINT.writeValueAsBytes(body));
        String principal = "console:" + AccessContext.current(request).user().subject();
        return PlatformContexts.call(
                Scopes.tenant(tenantId == null ? PLATFORM_SCOPE : tenantId),
                () -> idempotency.execute(principal, idempotencyKey, fingerprint, action));
    }
}
