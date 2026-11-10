package cn.refinex.meridian.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.security.apikey.ConsoleTokenAuthenticator;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** {@link ConsoleAuthAutoConfiguration}：配置了签发方才校验 OIDC 令牌，没有来源时一律拒绝；公开 API 还需要项目权限的来源。 */
class ConsoleAuthAutoConfigurationTest {

    private final WebApplicationContextRunner servlet = new WebApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(ServiceCredentialAutoConfiguration.class, ConsoleAuthAutoConfiguration.class))
            .withBean(
                    "handlerExceptionResolver",
                    HandlerExceptionResolver.class,
                    () -> (req, res, h, e) -> new ModelAndView());

    private final ReactiveWebApplicationContextRunner reactive = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ServiceCredentialAutoConfiguration.class, ConsoleAuthAutoConfiguration.class));

    @Test
    void withAnIssuerTokensAreVerifiedAsOidc() {
        servlet.withPropertyValues(
                        "meridian.security.oidc.issuer=http://127.0.0.1:1/realms/x",
                        "meridian.security.oidc.audience=meridian")
                .run(context -> {
                    assertThat(context.getBean(ConsoleUserResolver.class)).isInstanceOf(OidcTokenVerifier.class);
                    assertThat(context).hasSingleBean(ConsoleUserFilter.class);
                });
    }

    @Test
    void withoutAnIssuerEveryTokenIsRejected() {
        servlet.run(
                context -> assertThat(context.getBean(ConsoleUserResolver.class).resolve("a.b.c"))
                        .isEmpty());
    }

    @Test
    void thePublicApiAcceptsConsoleUsersOnlyWithOidcAndAControlPlane() {
        reactive.withPropertyValues(
                        "meridian.security.oidc.issuer=http://127.0.0.1:1/realms/x",
                        "meridian.security.oidc.audience=meridian",
                        "meridian.security.service-client.control-plane-url=http://127.0.0.1:1")
                .run(context -> {
                    assertThat(context).hasSingleBean(ConsoleTokenAuthenticator.class);
                    assertThat(context.getBean(ProjectAccessResolver.class))
                            .isInstanceOf(ControlPlaneProjectAccessResolver.class);
                    assertThat(context).doesNotHaveBean(ConsoleUserFilter.class);
                });
        reactive.withPropertyValues(
                        "meridian.security.oidc.issuer=http://127.0.0.1:1/realms/x",
                        "meridian.security.oidc.audience=meridian")
                .run(context -> assertThat(context).doesNotHaveBean(ConsoleTokenAuthenticator.class));
        reactive.withPropertyValues("meridian.security.service-client.control-plane-url=http://127.0.0.1:1")
                .run(context -> assertThat(context).doesNotHaveBean(ConsoleTokenAuthenticator.class));
    }
}
