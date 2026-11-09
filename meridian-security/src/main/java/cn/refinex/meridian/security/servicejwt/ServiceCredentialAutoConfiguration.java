package cn.refinex.meridian.security.servicejwt;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 服务凭据的自动配置：入站的解析器、认证器与两种 Web 栈的过滤器，出站的凭据来源与服务间调用客户端（ADR-0042）。
 *
 * <p>解析器的选择与 API Key 相同：应用提供了 {@link ServiceCredentialResolver}（T14b 的服务 JWT）时用它；否则启用了开发期服务凭据时用
 * {@link DevServiceCredentialResolver}，它只允许在 {@code dev}、{@code test} profile 启用，其他 profile 中启用直接让启动失败；都没有时
 * 一律拒绝，内部接口不会在没有认证来源时被放行。
 */
@AutoConfiguration
@EnableConfigurationProperties({DevServiceCredentialProperties.class, ServiceClientProperties.class})
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
     * 服务凭据认证器，两种 Web 栈共用。
     *
     * @param resolver 服务凭据解析器
     * @return 认证器
     */
    @Bean
    @ConditionalOnMissingBean
    public ServiceCredentialAuthenticator serviceCredentialAuthenticator(ServiceCredentialResolver resolver) {
        return new ServiceCredentialAuthenticator(resolver);
    }

    /**
     * WebFlux 服务的内部接口过滤器。
     *
     * @param authenticator 服务凭据认证器
     * @return 过滤器
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    public ServiceCredentialWebFilter serviceCredentialWebFilter(ServiceCredentialAuthenticator authenticator) {
        return new ServiceCredentialWebFilter(authenticator);
    }

    /**
     * 出站服务凭据。应用提供了 {@link ServiceCredentialProvider}（T14b 的服务 JWT）时用它；否则用开发期静态凭据，它与入站的开发期凭据一样
     * 只允许在 {@code dev}、{@code test} profile 设置；都没有时，调用其他服务的请求在发出前失败。
     *
     * @param properties 服务间调用的配置
     * @param environment 环境，检查当前 profile
     * @return 凭据来源
     * @throws IllegalStateException 在 {@code dev}、{@code test} 之外的 profile 设置了开发期出站凭据时
     */
    @Bean
    @ConditionalOnMissingBean
    public ServiceCredentialProvider serviceCredentialProvider(
            ServiceClientProperties properties, Environment environment) {
        String credential = properties.credential();
        if (credential == null || credential.isBlank()) {
            return () -> {
                throw new IllegalStateException(
                        "没有出站服务凭据：开发与测试设置 meridian.security.service-client.credential，T14b 起使用服务 JWT");
            };
        }
        if (!environment.matchesProfiles(DEV_PROFILES)) {
            throw new IllegalStateException(
                    "开发期出站服务凭据（meridian.security.service-client.credential）只能在 dev 或 test profile 中设置，当前 profile："
                            + Arrays.toString(environment.getActiveProfiles()));
        }
        return () -> credential;
    }

    /**
     * 服务间调用客户端的构造入口。
     *
     * @param properties 服务间调用的配置
     * @param credentials 出站服务凭据
     * @return 构造入口
     */
    @Bean
    @ConditionalOnMissingBean
    public ServiceRestClients serviceRestClients(
            ServiceClientProperties properties, ServiceCredentialProvider credentials) {
        return new ServiceRestClients(properties, credentials);
    }

    /** MVC 服务（control-plane、knowledge）的内部接口过滤器。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.HandlerExceptionResolver")
    static class Servlet {

        @Bean
        ServiceCredentialFilter serviceCredentialFilter(
                ServiceCredentialAuthenticator authenticator,
                @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
            return new ServiceCredentialFilter(authenticator, errors);
        }
    }
}
