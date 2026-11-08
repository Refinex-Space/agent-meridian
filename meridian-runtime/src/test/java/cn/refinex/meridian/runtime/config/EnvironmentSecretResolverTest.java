package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.error.NotFoundException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** 凭据解析的开发实现：只读 {@code *_API_KEY} 环境变量，明文不出现在 toString 中。 */
class EnvironmentSecretResolverTest {

    private final EnvironmentSecretResolver resolver = new EnvironmentSecretResolver(
            Map.of("ACME_API_KEY", "sk-test-value", "MERIDIAN_APP_PASSWORD", "db-secret")::get);

    @Test
    void resolvesApiKeyVariables() {
        StepVerifier.create(resolver.resolve("ACME_API_KEY"))
                .assertNext(secret -> {
                    assertThat(secret.reveal()).isEqualTo("sk-test-value");
                    assertThat(secret.toString()).doesNotContain("sk-test-value");
                })
                .verifyComplete();
    }

    @Test
    void refusesOtherVariablesAndReportsMissingOnes() {
        StepVerifier.create(resolver.resolve("MERIDIAN_APP_PASSWORD"))
                .expectError(ForbiddenException.class)
                .verify();
        StepVerifier.create(resolver.resolve("absent_api_key"))
                .expectError(ForbiddenException.class)
                .verify();
        StepVerifier.create(resolver.resolve("OTHER_API_KEY"))
                .expectError(NotFoundException.class)
                .verify();
    }
}
