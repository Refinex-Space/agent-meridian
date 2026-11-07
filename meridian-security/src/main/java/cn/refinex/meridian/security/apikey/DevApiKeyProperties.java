package cn.refinex.meridian.security.apikey;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 开发期 API Key 的配置（{@code meridian.security.dev-api-keys}），只在 {@code dev} 与 {@code test} profile 中允许启用；
 * 在其他 profile 中启用会让应用启动失败（{@link ApiKeyAutoConfiguration}）。
 *
 * @param enabled 是否启用 {@link DevApiKeyResolver}；默认 false
 * @param keys 开发用的 API Key 及其主体；可为空列表
 */
@ConfigurationProperties("meridian.security.dev-api-keys")
public record DevApiKeyProperties(boolean enabled, List<Key> keys) {

    /** 规整空列表。 */
    public DevApiKeyProperties {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }

    /**
     * 一个开发用的 API Key。明文只能来自环境变量或本地配置，不能提交到仓库（编码规范「配置」）。
     *
     * @param key API Key 明文；必填
     * @param apiKeyId API Key ID（{@code key_…}）；必填
     * @param tenantId 租户 ID；必填
     * @param projectId 项目 ID；必填
     * @param scopes 作用域取值，例如 {@code sessions:read}；可为空列表
     */
    public record Key(String key, String apiKeyId, String tenantId, String projectId, List<String> scopes) {

        /** 规整空列表。 */
        public Key {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }
}
