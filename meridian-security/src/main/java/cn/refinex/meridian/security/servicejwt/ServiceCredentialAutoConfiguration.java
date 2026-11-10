package cn.refinex.meridian.security.servicejwt;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 服务 JWT 的自动配置：入站的校验器、认证器与两种 Web 栈的过滤器，出站的签发客户端与服务间调用客户端（ADR-0044）。
 *
 * <p>校验器的选择：应用提供了 {@link ServiceCredentialResolver} 时用它（control-plane 用进程内的密钥集）；否则配置了本服务名与
 * JWKS 地址（或 control-plane 地址）时用 {@link ServiceJwtVerifier#remote}；都没有时一律拒绝，内部接口不会在没有认证来源时被放行。
 * 出站：配置了本服务名、客户端密钥与 control-plane 地址时用 {@link ServiceTokenClient}；否则调用其他服务的请求在发出前失败。
 */
@AutoConfiguration
@EnableConfigurationProperties({ServiceClientProperties.class, ServiceJwtProperties.class})
public class ServiceCredentialAutoConfiguration {

    /**
     * 服务 JWT 校验器。
     *
     * @param jwt 服务 JWT 配置
     * @param client 服务间调用配置，取 control-plane 地址
     * @return 校验器，或拒绝一切的解析器
     * @throws IllegalStateException JWKS 地址不合法时
     */
    @Bean
    @ConditionalOnMissingBean
    public ServiceCredentialResolver serviceCredentialResolver(
            ServiceJwtProperties jwt, ServiceClientProperties client) {
        String jwksUrl = jwt.jwksUrl() != null && !jwt.jwksUrl().isBlank()
                ? jwt.jwksUrl()
                : client.hasControlPlane() ? client.controlPlaneUrl() + ServiceJwts.JWKS_PATH : null;
        if (!jwt.hasService() || jwksUrl == null) {
            // 不知道自己是谁或公钥在哪：内部接口一律拒绝
            return credential -> Optional.empty();
        }
        try {
            return ServiceJwtVerifier.remote(
                    URI.create(jwksUrl).toURL(), jwt.issuer(), jwt.service(), jwt.jwksCacheTtl());
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException("服务 JWT 的 JWKS 地址不合法：" + jwksUrl, e);
        }
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
     * 出站服务凭据：向 control-plane 换取的服务 JWT。
     *
     * @param jwt 服务 JWT 配置
     * @param client 服务间调用配置
     * @param clock 时钟；没有时用系统时钟
     * @return 凭据来源
     */
    @Bean
    @ConditionalOnMissingBean
    public ServiceCredentialProvider serviceCredentialProvider(
            ServiceJwtProperties jwt, ServiceClientProperties client, ObjectProvider<Clock> clock) {
        if (!jwt.hasService() || !jwt.hasClientSecret() || !client.hasControlPlane()) {
            return audience -> {
                throw new IllegalStateException("没有出站服务凭据：配置 meridian.security.service-jwt.service、client-secret 与"
                        + " meridian.security.service-client.control-plane-url 后才能调用 " + audience);
            };
        }
        return new ServiceTokenClient(
                ServiceRestClients.builder(client.controlPlaneUrl(), client).build(),
                jwt.service(),
                jwt.clientSecret(),
                clock.getIfAvailable(Clock::systemUTC));
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
