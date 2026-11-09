package cn.refinex.meridian.security.servicejwt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

/** {@link ServiceCredentialAutoConfiguration}：开发期服务凭据只能在 dev、test profile 启用，没有来源时一律拒绝（T09b-A02）。 */
class ServiceCredentialAutoConfigurationTest {

    private static final String[] DEV_CREDENTIAL = {
        "meridian.security.dev-service-credentials.enabled=true",
        "meridian.security.dev-service-credentials.credentials[0].credential=svc_dev_test_secret",
        "meridian.security.dev-service-credentials.credentials[0].service=knowledge"
    };

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServiceCredentialAutoConfiguration.class));

    @Test
    void devCredentialsWorkInTheDevAndTestProfiles() {
        for (String profile : new String[] {"dev", "test"}) {
            runner.withPropertyValues(DEV_CREDENTIAL)
                    .withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        ServiceCredentialResolver resolver = context.getBean(ServiceCredentialResolver.class);
                        assertThat(resolver).isInstanceOf(DevServiceCredentialResolver.class);
                        assertThat(resolver.resolve("svc_dev_test_secret")).contains(new ServicePrincipal("knowledge"));
                        assertThat(resolver.resolve("svc_other")).isEmpty();
                        assertThat(context).hasSingleBean(ServiceCredentialWebFilter.class);
                    });
        }
    }

    @Test
    void enablingDevCredentialsOutsideDevAndTestFailsTheStartup() {
        runner.withPropertyValues(DEV_CREDENTIAL)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("只能在 dev 或 test profile 中启用"));
    }

    @Test
    void outgoingDevCredentialIsOnlyAllowedInTheDevAndTestProfiles() {
        runner.withPropertyValues(
                        "spring.profiles.active=dev", "meridian.security.service-client.credential=svc_dev_out")
                .run(context -> assertThat(
                                context.getBean(ServiceCredentialProvider.class).credential())
                        .isEqualTo("svc_dev_out"));
        runner.withPropertyValues(
                        "spring.profiles.active=prod", "meridian.security.service-client.credential=svc_dev_out")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("只能在 dev 或 test profile 中设置"));
    }

    @Test
    void withoutAnOutgoingCredentialCallsFailBeforeBeingSent() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed();
            ServiceCredentialProvider provider = context.getBean(ServiceCredentialProvider.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(provider::credential)
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void withoutAnyCredentialSourceEveryCredentialIsRejected() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ServiceCredentialResolver.class).resolve("svc_dev_test_secret"))
                    .isEmpty();
        });
    }
}
