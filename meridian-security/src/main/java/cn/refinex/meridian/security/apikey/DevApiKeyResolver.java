package cn.refinex.meridian.security.apikey;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 开发期的 {@link ApiKeyResolver}：API Key 与主体来自配置 {@link DevApiKeyProperties}，只在 {@code test}
 * profile 生效（T07；T14a 起 dev profile 也经 control-plane 校验）。正式来源是 {@link ControlPlaneApiKeyResolver}。
 *
 * <p>与正式实现一样只保存 API Key 的 SHA-256 哈希，按哈希查找，不比较明文。线程安全：创建后不可变。
 */
public final class DevApiKeyResolver implements ApiKeyResolver {

    private final Map<String, ApiKeyPrincipal> byHash;

    /**
     * 按配置创建解析器。
     *
     * @param properties 开发期 API Key 配置
     * @throws IllegalStateException 有空的 API Key、重复的 API Key、缺少主体字段或未知的作用域时
     */
    public DevApiKeyResolver(DevApiKeyProperties properties) {
        Map<String, ApiKeyPrincipal> keys = new HashMap<>();
        for (DevApiKeyProperties.Key key : properties.keys()) {
            if (key.key() == null || key.key().isBlank()) {
                throw new IllegalStateException("开发期 API Key 不能为空：" + key.apiKeyId());
            }
            if (isBlank(key.apiKeyId()) || isBlank(key.tenantId()) || isBlank(key.projectId())) {
                throw new IllegalStateException("开发期 API Key 必须给出 api-key-id、tenant-id 与 project-id");
            }
            Set<ApiKeyScope> scopes = EnumSet.noneOf(ApiKeyScope.class);
            for (String scope : key.scopes()) {
                scopes.add(ApiKeyScope.fromValue(scope)
                        .orElseThrow(() -> new IllegalStateException("未知的作用域：" + scope + "（" + key.apiKeyId() + "）")));
            }
            ApiKeyPrincipal principal = new ApiKeyPrincipal(key.apiKeyId(), key.tenantId(), key.projectId(), scopes);
            if (keys.put(ApiKeyHashes.sha256(key.key()), principal) != null) {
                throw new IllegalStateException("开发期 API Key 重复：" + key.apiKeyId());
            }
        }
        this.byHash = Map.copyOf(keys);
    }

    @Override
    public Optional<ApiKeyPrincipal> resolve(String apiKey) {
        return Optional.ofNullable(byHash.get(ApiKeyHashes.sha256(apiKey)));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
