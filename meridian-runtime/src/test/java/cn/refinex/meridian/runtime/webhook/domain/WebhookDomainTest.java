package cn.refinex.meridian.runtime.webhook.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.engine.spi.event.EventType;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Webhook 的纯逻辑（T17a，ADR-0049）：重试计划、签名、订阅匹配、可订阅的事件。 */
class WebhookDomainTest {

    private static final Instant T0 = Instant.parse("2026-11-12T08:00:00Z");

    @Test
    void retryScheduleAllowsEightAttemptsWithinTwentyFourHours() {
        Instant deadline = T0.plus(RetrySchedule.WINDOW);
        Instant now = T0;
        int attempts = 1;
        while (true) {
            var next = RetrySchedule.next(attempts, now, deadline);
            if (next.isEmpty()) {
                break;
            }
            assertThat(next.get()).isAfter(now);
            now = next.get();
            attempts++;
        }
        assertThat(attempts).isEqualTo(RetrySchedule.MAX_ATTEMPTS);
        assertThat(now).isBefore(deadline);
        assertThat(RetrySchedule.next(1, T0, T0.plusSeconds(10)))
                .as("超过截止时间不再重试")
                .isEmpty();
        assertThat(RetrySchedule.next(1, T0, deadline)).contains(T0.plusSeconds(30));
        assertThat(RetrySchedule.DELAYS.stream().reduce(Duration.ZERO, Duration::plus))
                .isLessThan(Duration.ofHours(12));
        assertThatThrownBy(() -> RetrySchedule.next(0, T0, deadline)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signatureIsHmacSha256OfTimestampDotBody() {
        // 参照值：printf '1700000000.{"id":"evt_1"}' | openssl dgst -sha256 -hmac whsec_test
        String header = WebhookSignature.header("whsec_test", 1_700_000_000L, "{\"id\":\"evt_1\"}");

        assertThat(header).startsWith("t=1700000000,v1=");
        assertThat(header.substring("t=1700000000,v1=".length()))
                .isEqualTo("c89214b5b5da833daed6f0b8c5bb6bd58cea9022bd80ccc78230f3942d632925");
        assertThat(WebhookSignature.hmac("whsec_test", "a")).isNotEqualTo(WebhookSignature.hmac("whsec_other", "a"));
    }

    @Test
    void subscriptionsMatchEnabledSubscribedTypesAndHideTheSecret() {
        WebhookSubscription webhook =
                new WebhookSubscription("whk_1", "https://example.com", Set.of("session.error"), true, "whsec_x");
        WebhookSubscription disabled =
                new WebhookSubscription("whk_2", "https://example.com", Set.of("session.error"), false, "whsec_y");

        assertThat(webhook.wants("session.error")).isTrue();
        assertThat(webhook.wants("session.status_idle")).isFalse();
        assertThat(disabled.wants("session.error")).isFalse();
        assertThat(webhook.toString()).doesNotContain("whsec_x");
        assertThat(WebhookEventTypes.subscribable(EventType.SESSION_STATUS_IDLE))
                .isTrue();
        assertThat(WebhookEventTypes.subscribable(EventType.SESSION_STATUS_RUNNING))
                .isFalse();
        assertThat(DeliveryStatus.of("failed")).isEqualTo(DeliveryStatus.FAILED);
        assertThatThrownBy(() -> DeliveryStatus.of("unknown")).isInstanceOf(IllegalArgumentException.class);
        ErrorCodes.verify(WebhookErrorCode.values());
    }
}
