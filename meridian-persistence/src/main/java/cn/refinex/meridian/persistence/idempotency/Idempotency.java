package cn.refinex.meridian.persistence.idempotency;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code Idempotency-Key} 的通用实现（架构设计「API 设计」中的「约定」）：
 * 24 小时内同键同请求体返回首次结果，同键不同请求体返回冲突。
 *
 * <p>流程：先占用键（并发的重复请求在这一步被挡住，得到"处理中"冲突），执行业务，保存结果。
 * 业务抛出异常时释放键，调用方可以用同一个键重试；业务正常返回的任何响应（包括 4xx）都会被保存并重放。
 * 处理中的记录超过 {@link #IN_PROGRESS_TIMEOUT} 仍未完成，视为处理节点已崩溃，允许新的请求接手。
 *
 * <p>HTTP 层（各服务的过滤器或处理器）负责读取请求头、计算指纹、把结果写回响应；本类不依赖 Web 框架。
 * 当前作用域必须绑定 {@code PlatformContext}，记录按租户隔离。
 *
 * <p>线程安全：无状态，可共享。
 */
public final class Idempotency {

    /** 记录保留时长，架构设计「API 设计」规定 24 小时。 */
    public static final Duration RETENTION = Duration.ofHours(24);

    /** 处理中记录的超时：平台的 POST 接口都是短请求，超过它仍未完成，视为处理节点已崩溃。 */
    public static final Duration IN_PROGRESS_TIMEOUT = Duration.ofMinutes(5);

    /** 幂等键的最大长度，超过即拒绝。 */
    public static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyStore store;
    private final Clock clock;

    /**
     * 创建幂等执行器。
     *
     * @param store 幂等键存储
     * @param clock 时钟
     */
    public Idempotency(IdempotencyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * 幂等地执行一次请求。
     *
     * @param principal 调用方身份，例如 API Key ID；同一租户内不同调用方的同名键互不影响
     * @param key 调用方提供的 {@code Idempotency-Key}
     * @param requestHash 请求指纹，用 {@link #fingerprint} 计算
     * @param action 首次请求时执行的业务，返回要保存的响应
     * @return 本次或首次的响应，以及是否为重放
     * @throws ValidationException 键为空或过长时
     * @throws ConflictException 同键不同请求体（{@code idempotency.key_reused}），或首次请求仍在处理中
     *     （{@code idempotency.in_progress}）时
     */
    public IdempotentResult execute(String principal, String key, String requestHash, Supplier<StoredResponse> action) {
        validateKey(key);
        Instant now = clock.instant();
        if (store.tryAcquire(principal, key, requestHash, now, now.plus(RETENTION), now.minus(IN_PROGRESS_TIMEOUT))) {
            StoredResponse response;
            try {
                response = action.get();
            } catch (RuntimeException e) {
                store.release(principal, key);
                throw e;
            }
            store.complete(principal, key, response);
            return new IdempotentResult(response, false);
        }
        IdempotencyStore.Entry entry = store.find(principal, key)
                // 占用失败后记录又被清理，只可能是记录恰好在两步之间过期，按处理中返回，调用方重试即可
                .orElseThrow(() -> inProgress());
        if (!entry.requestHash().equals(requestHash)) {
            throw new ConflictException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key 已在 24 小时内用于不同的请求");
        }
        if (!entry.completed()) {
            throw inProgress();
        }
        return new IdempotentResult(new StoredResponse(entry.responseStatus(), entry.responseBody()), true);
    }

    /**
     * 计算请求指纹：方法、路径与请求体的 SHA-256。
     *
     * @param method HTTP 方法
     * @param path 请求路径（不含主机，可含查询串）
     * @param body 请求体，可为空数组
     * @return 小写十六进制摘要
     */
    public static String fingerprint(String method, String path, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 规定必须提供的算法
            throw new IllegalStateException(e);
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key 不能为空，长度不超过 " + MAX_KEY_LENGTH,
                    List.of(new FieldViolation("Idempotency-Key", "不能为空，长度不超过 " + MAX_KEY_LENGTH)));
        }
    }

    private static ConflictException inProgress() {
        return new ConflictException(CommonErrorCode.IDEMPOTENCY_IN_PROGRESS, "相同 Idempotency-Key 的请求正在处理，请稍后重试");
    }
}
