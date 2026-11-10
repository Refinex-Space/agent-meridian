package cn.refinex.meridian.controlplane.servicetoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.controlplane.servicetoken.domain.ServiceClient;
import cn.refinex.meridian.controlplane.servicetoken.domain.ServiceTokenErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ServiceClient}：只保存密钥摘要，按目标服务限定；配置不完整或密钥太短时启动失败（ADR-0044）。 */
class ServiceClientTest {

    @Test
    void secretsAreComparedByDigestAndAudiencesAreRestricted() {
        ServiceClient client = ServiceClient.of("runtime", "runtime-client-secret", List.of("control-plane"));

        assertThat(client.matches("runtime-client-secret")).isTrue();
        assertThat(client.matches("runtime-client-secreT")).isFalse();
        assertThat(client.matches("")).isFalse();
        assertThat(client.mayCall("control-plane")).isTrue();
        assertThat(client.mayCall("runtime")).isFalse();
        assertThat(client.toString()).contains("runtime").doesNotContain("runtime-client-secret");
        assertThat(client)
                .isEqualTo(ServiceClient.of("runtime", "another-secret-value", List.of("knowledge")))
                .hasSameHashCodeAs(ServiceClient.of("runtime", "another-secret-value", List.of("knowledge")))
                .isNotEqualTo(ServiceClient.of("knowledge", "runtime-client-secret", List.of("runtime")));
    }

    @Test
    void incompleteOrWeakConfigurationIsRejected() {
        assertThatThrownBy(() -> ServiceClient.of("runtime", "short", List.of("control-plane")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16");
        assertThatThrownBy(() -> ServiceClient.of("runtime", null, List.of("control-plane")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ServiceClient.of(" ", "runtime-client-secret", List.of("control-plane")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ServiceClient.of("runtime", "runtime-client-secret", List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theErrorCodeIsAnUnauthenticatedProblem() {
        assertThat(ServiceTokenErrorCode.INVALID_SERVICE_CLIENT.code()).isEqualTo("auth.invalid_service_client");
        assertThat(ServiceTokenErrorCode.INVALID_SERVICE_CLIENT.status()).isEqualTo(401);
        assertThat(ServiceTokenErrorCode.INVALID_SERVICE_CLIENT.title()).isNotBlank();
    }
}
