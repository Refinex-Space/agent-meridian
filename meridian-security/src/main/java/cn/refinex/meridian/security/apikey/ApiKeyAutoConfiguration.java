package cn.refinex.meridian.security.apikey;

import cn.refinex.meridian.security.servicejwt.ServiceClientProperties;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialAutoConfiguration;
import cn.refinex.meridian.security.servicejwt.ServiceRestClients;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * API Key 认证的自动配置：解析器、认证器、限流器与 WebFlux 过滤器。
 *
 * <p>解析器的选择（ADR-0042）：应用提供了 {@link ApiKeyResolver} 时用它；否则配置了 control-plane 地址
 * （{@code meridian.security.service-client.control-plane-url}）时用 {@link ControlPlaneApiKeyResolver}；否则启用了开发期 API Key 时用
 * {@link DevApiKeyResolver}，它只允许在 {@code test} profile 启用（T14a 起 runtime 的 {@code dev} profile 也经 control-plane 校验），
 * 在其他 profile 中启用直接让启动失败；都没有时一律拒绝，保证公开 API 不会在没有认证来源时被放行。
 */
@AutoConfiguration(
        after = ServiceCredentialAutoConfiguration.class,
        afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@EnableConfigurationProperties(DevApiKeyProperties.class)
public class ApiKeyAutoConfiguration {

    /** 允许启用开发期 API Key 的 profile：只有测试（A05）。 */
    static final String[] DEV_PROFILES = {"test"};

    /**
     * API Key 解析器。
     *
     * @param properties 开发期 API Key 配置
     * @param environment 环境，用于检查当前 profile
     * @param serviceClient 服务间调用配置，取 control-plane 地址
     * @param clients 服务间调用客户端的构造入口
     * @param clock 时钟，判断 Key 是否过期；没有时用系统时钟
     * @return control-plane 解析器、开发期解析器，或拒绝一切的解析器
     * @throws IllegalStateException 在 {@code test} 之外的 profile 启用开发期 API Key 时
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiKeyResolver apiKeyResolver(
            DevApiKeyProperties properties,
            Environment environment,
            ServiceClientProperties serviceClient,
            ServiceRestClients clients,
            ObjectProvider<Clock> clock) {
        if (properties.enabled() && !environment.matchesProfiles(DEV_PROFILES)) {
            throw new IllegalStateException(
                    "开发期 API Key（meridian.security.dev-api-keys.enabled）只能在 test profile 中启用，当前 profile："
                            + Arrays.toString(environment.getActiveProfiles()));
        }
        if (serviceClient.hasControlPlane()) {
            return new ControlPlaneApiKeyResolver(
                    clients.create(serviceClient.controlPlaneUrl()), clock.getIfAvailable(Clock::systemUTC));
        }
        if (!properties.enabled()) {
            // 没有 API Key 来源：一律拒绝
            return apiKey -> Optional.empty();
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
     * @param rateLimiter 限流器；没有 Redis 时不存在，不限流
     * @return 过滤器
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    public ApiKeyWebFilter apiKeyWebFilter(
            ApiKeyAuthenticator authenticator, ObjectProvider<ApiKeyRateLimiter> rateLimiter) {
        return new ApiKeyWebFilter(authenticator, rateLimiter.getIfAvailable());
    }

    /** 应用有 Redis 时启用按 Key 限流（ADR-0042）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    static class RateLimiting {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(StringRedisTemplate.class)
        ApiKeyRateLimiter apiKeyRateLimiter(StringRedisTemplate redis, ObjectProvider<Clock> clock) {
            return new RedisApiKeyRateLimiter(redis, clock.getIfAvailable(Clock::systemUTC));
        }
    }
}
