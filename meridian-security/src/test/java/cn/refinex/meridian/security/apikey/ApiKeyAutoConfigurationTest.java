package cn.refinex.meridian.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.MeridianException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

/** {@link ApiKeyAutoConfiguration}：开发期 API Key 只能在 dev、test profile 启用（T07-A08），没有来源时一律拒绝。 */
class ApiKeyAutoConfigurationTest {

    private static final String[] DEV_KEY = {
        "meridian.security.dev-api-keys.enabled=true",
        "meridian.security.dev-api-keys.keys[0].key=mk_dev_test_secret",
        "meridian.security.dev-api-keys.keys[0].api-key-id=key_dev",
        "meridian.security.dev-api-keys.keys[0].tenant-id=ten_dev",
        "meridian.security.dev-api-keys.keys[0].project-id=prj_dev",
        "meridian.security.dev-api-keys.keys[0].scopes[0]=sessions:read"
    };

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApiKeyAutoConfiguration.class));

    @Test
    void devKeysWorkInTheDevAndTestProfiles() {
        for (String profile : new String[] {"dev", "test"}) {
            runner.withPropertyValues(DEV_KEY)
                    .withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(ApiKeyResolver.class)).isInstanceOf(DevApiKeyResolver.class);
                        assertThat(context.getBean(ApiKeyAuthenticator.class)
                                        .authenticate("Bearer mk_dev_test_secret")
                                        .projectId())
                                .isEqualTo("prj_dev");
                        assertThat(context).hasSingleBean(ApiKeyWebFilter.class);
                    });
        }
    }

    @Test
    void enablingDevKeysOutsideDevAndTestFailsTheStartup() {
        runner.withPropertyValues(DEV_KEY)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("只能在 dev 或 test profile 中启用"));
        runner.withPropertyValues(DEV_KEY).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void withoutAnyKeySourceEveryKeyIsRejected() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed();
            ApiKeyAuthenticator authenticator = context.getBean(ApiKeyAuthenticator.class);
            assertThatThrownBy(() -> authenticator.authenticate("Bearer anything"))
                    .isInstanceOfSatisfying(
                            MeridianException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ApiKeyErrorCode.INVALID_API_KEY));
        });
    }
}
