package cn.refinex.meridian.controlplane.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.controlplane.secret.domain.SecretCipher;
import cn.refinex.meridian.controlplane.webhook.domain.Webhook;
import cn.refinex.meridian.controlplane.webhook.domain.WebhookErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Webhook 的领域规则（T17a，ADR-0049）：订阅事件的规整、错误码、签名密钥密文绑定租户与 Webhook。 */
class WebhookDomainTest {

    @Test
    void eventsAreDeduplicatedAndOrderedByTheFixedList() {
        assertThat(Webhook.normalize(List.of("session.status_terminated", "session.error", "session.error")))
                .containsExactly("session.error", "session.status_terminated");
    }

    @Test
    void unknownOrEmptyEventsAreRejected() {
        assertThatThrownBy(() -> Webhook.normalize(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Webhook.normalize(List.of("session.status_running")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session.status_running");
    }

    @Test
    void errorCodesAreWellFormed() {
        ErrorCodes.verify(WebhookErrorCode.values());
    }

    @Test
    void signingSecretCiphertextIsBoundToTenantAndWebhook() {
        byte[] key = new byte[SecretCipher.KEY_BYTES];
        new SecureRandom().nextBytes(key);
        byte[] ciphertext = SecretCipher.encrypt(
                key,
                "whsec_x".getBytes(StandardCharsets.UTF_8),
                SecretCipher.webhookAad("ten_a", "whk_a"),
                new SecureRandom());

        assertThat(new String(
                        SecretCipher.decrypt(key, ciphertext, SecretCipher.webhookAad("ten_a", "whk_a")),
                        StandardCharsets.UTF_8))
                .isEqualTo("whsec_x");
        assertThatThrownBy(() -> SecretCipher.decrypt(key, ciphertext, SecretCipher.webhookAad("ten_a", "whk_b")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecretCipher.decrypt(key, ciphertext, SecretCipher.secretAad("ten_a", "whk_a", 1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
