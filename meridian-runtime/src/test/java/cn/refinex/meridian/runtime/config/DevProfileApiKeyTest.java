package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.security.apikey.ApiKeyAutoConfiguration;
import cn.refinex.meridian.security.apikey.ApiKeyResolver;
import cn.refinex.meridian.security.apikey.ControlPlaneApiKeyResolver;
import cn.refinex.meridian.security.apikey.DevApiKeyResolver;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialAutoConfiguration;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * T14a-A05：runtime 在非测试 profile 下不再使用 {@code DevApiKeyResolver}。{@code dev} profile 的配置不启用开发期 API Key，而是配置
 * control-plane 地址；按这份配置装配出的解析器是 {@link ControlPlaneApiKeyResolver}。在 {@code dev} 中启用开发期 API Key 会让启动失败，
 * 见 {@code meridian-security} 的 {@code ApiKeyAutoConfigurationTest}。
 */
class DevProfileApiKeyTest {

    @Test
    void theDevProfileVerifiesKeysWithTheControlPlane() throws IOException {
        PropertySource<?> dev = new YamlPropertySourceLoader()
                .load("application-dev", new ClassPathResource("application-dev.yml"))
                .getFirst();

        assertThat(dev.getProperty("meridian.security.dev-api-keys.enabled")).isNull();
        assertThat(dev.getProperty("meridian.security.service-client.control-plane-url"))
                .isNotNull();

        new ReactiveWebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ServiceCredentialAutoConfiguration.class, ApiKeyAutoConfiguration.class))
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "meridian.security.service-client.control-plane-url=http://127.0.0.1:18081",
                        "meridian.security.service-jwt.service=runtime",
                        "meridian.security.service-jwt.client-secret=dev-runtime-client-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ApiKeyResolver.class))
                            .isInstanceOf(ControlPlaneApiKeyResolver.class)
                            .isNotInstanceOf(DevApiKeyResolver.class);
                });
    }
}
