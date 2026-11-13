package cn.refinex.meridian.security.oidc;

import cn.refinex.meridian.security.apikey.ConsoleTokenAuthenticator;
import cn.refinex.meridian.security.servicejwt.ServiceClientProperties;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialAutoConfiguration;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.security.servicejwt.ServiceRestClients;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 控制台用户认证的自动配置（ADR-0043、ADR-0044）：OIDC 令牌的校验器；MVC 服务（control-plane）的管理 API 过滤器；WebFlux 服务
 * （runtime）的公开 API 上，带 {@code Meridian-Project} 的控制台用户认证。
 *
 * <p>校验器的选择：应用提供了 {@link ConsoleUserResolver} 时用它；否则配置了 {@code meridian.security.oidc.issuer} 时用
 * {@link OidcTokenVerifier}；都没有时一律拒绝，管理 API 不会在没有身份来源时被放行。
 */
@AutoConfiguration(after = ServiceCredentialAutoConfiguration.class)
@ConditionalOnWebApplication
@EnableConfigurationProperties(OidcProperties.class)
public class ConsoleAuthAutoConfiguration {

    /**
     * 控制台用户令牌的校验器。
     *
     * @param properties OIDC 配置
     * @return OIDC 校验器，或拒绝一切的解析器
     */
    @Bean
    @ConditionalOnMissingBean
    public ConsoleUserResolver consoleUserResolver(OidcProperties properties) {
        if (!properties.enabled()) {
            // 没有配置身份提供方：一律拒绝
            return token -> Optional.empty();
        }
        return new OidcTokenVerifier(properties);
    }

    /** MVC 服务的管理 API 过滤器。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.HandlerExceptionResolver")
    static class Servlet {

        @Bean
        ConsoleUserFilter consoleUserFilter(
                ConsoleUserResolver resolver, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
            return new ConsoleUserFilter(resolver, errors);
        }

        /**
         * 没有进程内项目权限的 MVC 服务（knowledge）经 control-plane 查询（{@code project-access:resolve} 接受 knowledge 的服务
         * JWT）；control-plane 自己提供进程内的实现，这里不再创建（T22）。
         *
         * @param client 服务间调用的配置
         * @param clients 服务间调用客户端
         * @return 经 control-plane 的项目权限
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "meridian.security.service-client", name = "control-plane-url")
        ProjectAccessResolver servletProjectAccessResolver(ServiceClientProperties client, ServiceRestClients clients) {
            return new ControlPlaneProjectAccessResolver(
                    clients.create(client.controlPlaneUrl(), ServiceJwts.CONTROL_PLANE));
        }

        /**
         * MVC 服务的公开 API 接受控制台用户（D55）：配置了 OIDC，且有项目权限的来源（control-plane 进程内查询，knowledge 经
         * control-plane）时启用。
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "meridian.security.oidc", name = "issuer")
        @ConditionalOnBean(ProjectAccessResolver.class)
        ConsoleTokenAuthenticator servletConsoleTokenAuthenticator(
                OidcProperties properties, ConsoleUserResolver users, ProjectAccessResolver access) {
            return new ConsoleApiAuthenticator(users, access, properties.rateLimitPerMinute());
        }
    }

    /**
     * WebFlux 服务的公开 API 接受控制台用户（ADR-0044）：配置了 OIDC，且有项目权限的来源（配置了 control-plane 地址，或应用提供了
     * {@link ProjectAccessResolver}）时启用；否则公开 API 只接受 API Key。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class Reactive {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "meridian.security.service-client", name = "control-plane-url")
        ProjectAccessResolver projectAccessResolver(ServiceClientProperties client, ServiceRestClients clients) {
            return new ControlPlaneProjectAccessResolver(
                    clients.create(client.controlPlaneUrl(), ServiceJwts.CONTROL_PLANE));
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "meridian.security.oidc", name = "issuer")
        @ConditionalOnBean(ProjectAccessResolver.class)
        ConsoleTokenAuthenticator consoleTokenAuthenticator(
                OidcProperties properties, ConsoleUserResolver users, ProjectAccessResolver access) {
            return new ConsoleApiAuthenticator(users, access, properties.rateLimitPerMinute());
        }
    }
}
