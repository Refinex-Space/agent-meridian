package cn.refinex.meridian.security.apikey;

import java.util.Optional;

/**
 * API Key 的来源：按明文 API Key 找到它的主体。
 *
 * <p>T07 提供开发期的 {@link DevApiKeyResolver}；T14a 提供按 SHA-256 哈希查找、缓存 60 秒、吊销即时失效的实现
 * （架构设计「认证」）。实现可以阻塞，WebFlux 中由 {@link ApiKeyWebFilter} 经 {@code Blocking} 调用。实现必须线程安全，
 * 并且不能记录 API Key 明文。
 */
public interface ApiKeyResolver {

    /**
     * 解析 API Key。
     *
     * @param apiKey {@code Authorization: Bearer} 中的明文 API Key，非空
     * @return 主体；API Key 不存在、已吊销或已过期时为空
     */
    Optional<ApiKeyPrincipal> resolve(String apiKey);
}
