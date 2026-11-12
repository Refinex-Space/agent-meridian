package cn.refinex.meridian.controlplane.apikey.app;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.controlplane.apikey.domain.ApiKey;
import cn.refinex.meridian.security.apikey.ApiKeyHashes;
import cn.refinex.meridian.security.apikey.ApiKeyPrincipal;
import cn.refinex.meridian.security.apikey.ApiKeyResolver;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * control-plane 自己的公开 API（{@code GET /v1/agents}，ADR-0047）的 API Key 解析：进程内按哈希查库（{@link ApiKeyVerifier}），不经内部
 * 接口、不缓存，吊销立即生效。它是应用提供的 {@link ApiKeyResolver}，{@code meridian-security} 的自动配置据此不再装配别的解析器（D55）。
 *
 * <p>线程安全：无状态。
 */
@Component
public class InProcessApiKeyResolver implements ApiKeyResolver {

    private final ApiKeyVerifier verifier;

    /**
     * 创建解析器。
     *
     * @param verifier 按哈希校验的用例
     */
    public InProcessApiKeyResolver(ApiKeyVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Optional<ApiKeyPrincipal> resolve(String apiKey) {
        ApiKey key;
        try {
            key = verifier.verify(ApiKeyHashes.sha256(apiKey));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
        return Optional.of(new ApiKeyPrincipal(
                key.id(),
                key.tenantId(),
                key.projectId(),
                key.scopes(),
                key.rateLimitPerMinute() > 0 ? key.rateLimitPerMinute() : null));
    }
}
