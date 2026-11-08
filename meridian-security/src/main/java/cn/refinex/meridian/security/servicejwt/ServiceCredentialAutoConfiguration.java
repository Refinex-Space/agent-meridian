package cn.refinex.meridian.security.servicejwt;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 服务凭据认证的自动配置：解析器与 WebFlux 过滤器。
 *
 * <p>解析器的选择与 API Key 相同：应用提供了 {@link ServiceCredentialResolver}（T14b 的服务 JWT）时用它；否则启用了开发期服务凭据时用
 * {@link DevServiceCredentialResolver}，它只允许在 {@code dev}、{@code test} profile 启用，其他 profile 中启用直接让启动失败；都没有时
 * 一律拒绝，内部接口不会在没有认证来源时被放行。
 */
@AutoConfiguration
@EnableConfigurationProperties(DevServiceCredentialProperties.class)
public class ServiceCredentialAutoConfiguration {

    /** 允许启用开发期服务凭据的 profile。 */
    static final String[] DEV_PROFILES = {"dev", "test"};

    /**
     * 服务凭据解析器。
     *
     * @param properties 开发期服务凭据配置
     * @param environment 环境，检查当前 profile
     * @return 开发期解析器，或拒绝一切的解析器
     * @throws IllegalStateException 在 {@code dev}、{@code test} 之外的 profile 启用开发期服务凭据时
     */
    @Bean
    @ConditionalOnMissingBean
    public ServiceCredentialResolver serviceCredentialResolver(
            DevServiceCredentialProperties properties, Environment environment) {
        if (!properties.enabled()) {
            // T14b 之前生产环境没有服务凭据来源：一律拒绝
            return credential -> Optional.empty();
        }
        if (!environment.matchesProfiles(DEV_PROFILES)) {
            throw new IllegalStateException(
                    "开发期服务凭据（meridian.security.dev-service-credentials.enabled）只能在 dev 或 test profile 中启用，当前 profile："
                            + Arrays.toString(environment.getActiveProfiles()));
        }
        return new DevServiceCredentialResolver(properties);
    }

    /**
     * WebFlux 服务的内部接口过滤器。
     *
     * @param resolver 服务凭据解析器
     * @return 过滤器
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    public ServiceCredentialWebFilter serviceCredentialWebFilter(ServiceCredentialResolver resolver) {
        return new ServiceCredentialWebFilter(resolver);
    }
}
