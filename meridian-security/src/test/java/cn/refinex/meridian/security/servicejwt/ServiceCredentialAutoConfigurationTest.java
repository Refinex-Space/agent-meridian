package cn.refinex.meridian.security.servicejwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.testsupport.auth.TestTokenIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

/** {@link ServiceCredentialAutoConfiguration}：配置了服务名与公钥地址才接受服务 JWT，没有来源时一律拒绝（ADR-0044）。 */
class ServiceCredentialAutoConfigurationTest {

    private static TestTokenIssuer issuer;

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServiceCredentialAutoConfiguration.class));

    @BeforeAll
    static void startIssuer() {
        issuer = TestTokenIssuer.start().client("runtime", "runtime-secret");
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    @Test
    void withAServiceNameAndJwksTheVerifierAcceptsTokensForThisService() {
        runner.withPropertyValues(
                        "meridian.security.service-jwt.service=runtime",
                        "meridian.security.service-jwt.jwks-url=" + issuer.jwksUrl())
                .run(context -> {
                    ServiceCredentialResolver resolver = context.getBean(ServiceCredentialResolver.class);
                    assertThat(resolver).isInstanceOf(ServiceJwtVerifier.class);
                    assertThat(resolver.resolve(issuer.serviceToken("knowledge", "runtime")))
                            .contains(new ServicePrincipal("knowledge"));
                    assertThat(resolver.resolve(issuer.serviceToken("knowledge", "control-plane")))
                            .isEmpty();
                    assertThat(context).hasSingleBean(ServiceCredentialWebFilter.class);
                });
    }

    @Test
    void theJwksDefaultsToTheControlPlane() {
        runner.withPropertyValues(
                        "meridian.security.service-jwt.service=runtime",
                        "meridian.security.service-client.control-plane-url=" + issuer.url())
                .run(context -> assertThat(context.getBean(ServiceCredentialResolver.class)
                                .resolve(issuer.serviceToken("knowledge", "runtime")))
                        .contains(new ServicePrincipal("knowledge")));
    }

    @Test
    void withoutAnyConfigurationEveryCredentialIsRejected() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ServiceCredentialResolver.class)
                            .resolve(issuer.serviceToken("knowledge", "runtime")))
                    .isEmpty();
        });
    }

    @Test
    void outgoingTokensAreExchangedWithTheClientSecret() {
        runner.withPropertyValues(
                        "meridian.security.service-jwt.service=runtime",
                        "meridian.security.service-jwt.client-secret=runtime-secret",
                        "meridian.security.service-client.control-plane-url=" + issuer.url())
                .run(context -> {
                    ServiceCredentialProvider provider = context.getBean(ServiceCredentialProvider.class);
                    assertThat(provider).isInstanceOf(ServiceTokenClient.class);
                    String token = provider.credential("control-plane");
                    assertThat(ServiceJwtVerifierTest.verifier(issuer, "control-plane")
                                    .resolve(token))
                            .contains(new ServicePrincipal("runtime"));
                });
    }

    @Test
    void withoutAClientSecretCallsFailBeforeBeingSent() {
        runner.withPropertyValues(
                        "meridian.security.service-jwt.service=runtime",
                        "meridian.security.service-client.control-plane-url=" + issuer.url())
                .run(context -> assertThatThrownBy(() ->
                                context.getBean(ServiceCredentialProvider.class).credential("control-plane"))
                        .isInstanceOf(IllegalStateException.class));
    }

    @Test
    void theClientSecretIsNotPrinted() {
        assertThat(new ServiceJwtProperties("runtime", "iss", "top-secret", null, null).toString())
                .doesNotContain("top-secret");
    }
}
