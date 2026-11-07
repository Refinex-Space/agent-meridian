package cn.refinex.meridian.security.apikey;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * API Key 认证的自动配置：解析器、认证器与 WebFlux 过滤器。
 *
 * <p>解析器的选择：应用（T14a）提供了 {@link ApiKeyResolver} 时用它；否则启用了开发期 API Key 时用 {@link DevApiKeyResolver}，
 * 而它只允许在 {@code dev}、{@code test} profile 启用，在其他 profile 中启用直接让启动失败；都没有时一律拒绝，
 * 保证公开 API 不会在没有认证来源时被放行。
 */
@AutoConfiguration
@EnableConfigurationProperties(DevApiKeyProperties.class)
public class ApiKeyAutoConfiguration {

    /** 允许启用开发期 API Key 的 profile。 */
    static final String[] DEV_PROFILES = {"dev", "test"};

    /**
     * API Key 解析器。
     *
     * @param properties 开发期 API Key 配置
     * @param environment 环境，用于检查当前 profile
     * @return 开发期解析器，或拒绝一切的解析器
     * @throws IllegalStateException 在 {@code dev}、{@code test} 之外的 profile 启用开发期 API Key 时
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiKeyResolver apiKeyResolver(DevApiKeyProperties properties, Environment environment) {
        if (!properties.enabled()) {
            // T14a 之前生产环境没有 API Key 来源：一律拒绝
            return apiKey -> Optional.empty();
        }
        if (!environment.matchesProfiles(DEV_PROFILES)) {
            throw new IllegalStateException(
                    "开发期 API Key（meridian.security.dev-api-keys.enabled）只能在 dev 或 test profile 中启用，当前 profile："
                            + Arrays.toString(environment.getActiveProfiles()));
        }
        return new DevApiKeyResolver(properties);
    }

    /**
     * API Key 认证器。
     *
     * @param resolver 解析器
     * @return 认证器
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiKeyAuthenticator apiKeyAuthenticator(ApiKeyResolver resolver) {
        return new ApiKeyAuthenticator(resolver);
    }

    /**
     * WebFlux 服务的认证过滤器。
     *
     * @param authenticator 认证器
     * @return 过滤器
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    public ApiKeyWebFilter apiKeyWebFilter(ApiKeyAuthenticator authenticator) {
        return new ApiKeyWebFilter(authenticator);
    }
}
