package cn.refinex.meridian.controlplane.web;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.controlplane.secret.app.DataKeys;
import cn.refinex.meridian.controlplane.secret.domain.SecretCipher;
import cn.refinex.meridian.persistence.idempotency.Idempotency;
import cn.refinex.meridian.persistence.idempotency.IdempotentResult;
import cn.refinex.meridian.persistence.idempotency.StoredResponse;
import cn.refinex.meridian.security.rbac.AccessContext;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Base64;
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
 *
 * <p>响应体含一次性密钥（API Key、Webhook 签名密钥）或请求含凭据值的写操作走 {@link #writeSensitive}：保存的响应体用租户数据密钥加密
 * （库中没有明文，重放时解密），指纹带凭据值的带密钥摘要（同键换值是 409）（R2 审查 K-02、K-05）。
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

    /** 加密保存的响应体的前缀，后接 Base64 的 {@code nonce || 密文 || 标签}。 */
    static final String ENCRYPTED_PREFIX = "enc:v1:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Idempotency idempotency;

    private final DataKeys dataKeys;

    /**
     * 创建执行入口。
     *
     * @param idempotency 幂等键的通用实现
     * @param dataKeys 租户数据密钥，加密含密钥的响应体
     */
    public AdminRequests(Idempotency idempotency, DataKeys dataKeys) {
        this.idempotency = idempotency;
        this.dataKeys = dataKeys;
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
     * 执行一个含敏感内容的写操作：响应体含一次性密钥，或请求含凭据值。有幂等键时，保存的响应体用租户数据密钥加密，敏感取值以带密钥
     * 摘要进入指纹；其余同 {@link #write}。
     *
     * @param request 当前请求
     * @param tenantId 路径中的租户
     * @param idempotencyKey {@code Idempotency-Key}；可为空，表示不做幂等
     * @param body 请求体中可以进入指纹的部分；可为空
     * @param sensitiveInput 请求中的敏感取值（凭据值）；可为空
     * @param status 成功时的状态码
     * @param responseType 响应体 DTO 的类型
     * @param action 业务，返回响应体 DTO
     * @param <T> 响应体类型
     * @return 响应
     */
    public <T> ResponseEntity<T> writeSensitive(
            HttpServletRequest request,
            String tenantId,
            String idempotencyKey,
            Object body,
            String sensitiveInput,
            HttpStatus status,
            Class<T> responseType,
            Supplier<T> action) {
        if (idempotencyKey == null) {
            return ResponseEntity.status(status).body(action.get());
        }
        // 数据密钥在业务事务之外取得（它有自己的事务），见 DataKeys
        byte[] dataKey = dataKeys.forTenant(tenantId);
        String principal = principal(request);
        byte[] aad = SecretCipher.idempotencyAad(tenantId, principal, idempotencyKey);
        Object fingerprinted = sensitiveInput == null
                ? body
                : new SensitiveFingerprint(body, SecretCipher.keyedDigest(dataKey, sensitiveInput));
        IdempotentResult result = execute(request, tenantId, idempotencyKey, fingerprinted, () -> {
            byte[] json = JsonMappers.lenient().writeValueAsBytes(action.get());
            String sealed = ENCRYPTED_PREFIX
                    + Base64.getEncoder().encodeToString(SecretCipher.encrypt(dataKey, json, aad, RANDOM));
            return new StoredResponse(status.value(), sealed);
        });
        String stored = result.response().body();
        byte[] json = SecretCipher.decrypt(
                dataKey, Base64.getDecoder().decode(stored.substring(ENCRYPTED_PREFIX.length())), aad);
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(result.response().status());
        if (result.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(JsonMappers.lenient().readValue(json, responseType));
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
        String principal = principal(request);
        return PlatformContexts.call(
                Scopes.tenant(tenantId == null ? PLATFORM_SCOPE : tenantId),
                () -> idempotency.execute(principal, idempotencyKey, fingerprint, action));
    }

    private static String principal(HttpServletRequest request) {
        return "console:" + AccessContext.current(request).user().subject();
    }

    /**
     * 含敏感取值的请求的指纹内容：可以进入指纹的部分加上敏感取值的带密钥摘要。
     *
     * @param body 请求体中可以进入指纹的部分；可为空
     * @param sensitiveDigest 敏感取值的带密钥摘要
     */
    record SensitiveFingerprint(Object body, String sensitiveDigest) {}
}
