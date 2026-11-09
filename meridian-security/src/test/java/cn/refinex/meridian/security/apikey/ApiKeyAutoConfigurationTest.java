package cn.refinex.meridian.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

/** {@link ApiKeyAutoConfiguration}：开发期 API Key 只能在 test profile 启用（T07-A08、T14a-A05），配置 control-plane 时经它校验，没有来源时一律拒绝。 */
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
            .withConfiguration(
                    AutoConfigurations.of(ServiceCredentialAutoConfiguration.class, ApiKeyAutoConfiguration.class));

    @Test
    void devKeysWorkInTheTestProfile() {
        runner.withPropertyValues(DEV_KEY)
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ApiKeyResolver.class)).isInstanceOf(DevApiKeyResolver.class);
                    assertThat(context.getBean(ApiKeyAuthenticator.class)
                                    .authenticate("Bearer mk_dev_test_secret")
                                    .projectId())
                            .isEqualTo("prj_dev");
                    assertThat(context).hasSingleBean(ApiKeyWebFilter.class);
                    // 没有 Redis 时不限流
                    assertThat(context).doesNotHaveBean(ApiKeyRateLimiter.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void enablingDevKeysOutsideTheTestProfileFailsTheStartup(String profile) {
        // T14a-A05：runtime 的 dev profile 不再使用 DevApiKeyResolver，经 control-plane 校验
        runner.withPropertyValues(DEV_KEY)
                .withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("只能在 test profile 中启用"));
    }

    @Test
    void enablingDevKeysWithoutAProfileFailsTheStartup() {
        runner.withPropertyValues(DEV_KEY).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void withAControlPlaneUrlKeysAreVerifiedByTheControlPlane() {
        runner.withPropertyValues(
                        "spring.profiles.active=dev",
                        "meridian.security.service-client.control-plane-url=http://127.0.0.1:1",
                        "meridian.security.service-client.credential=svc_dev_test_secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ApiKeyResolver.class)).isInstanceOf(ControlPlaneApiKeyResolver.class);
                });
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
