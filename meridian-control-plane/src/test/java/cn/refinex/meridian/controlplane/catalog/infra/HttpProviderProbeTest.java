package cn.refinex.meridian.controlplane.catalog.infra;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.catalog.domain.ConnectivityCheck;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderType;
import java.net.http.HttpRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 各供应商类型的探测请求与按状态码的结果（ADR-0053）。 */
class HttpProviderProbeTest {

    @Test
    void eachProviderTypeListsItsModelsWithItsOwnCredentialHeader() {
        HttpRequest openai =
                HttpProviderProbe.request(ProviderType.OPENAI_COMPATIBLE, "https://llm.example.com/v1/", "k1");
        assertThat(openai.uri().toString()).isEqualTo("https://llm.example.com/v1/models");
        assertThat(openai.headers().firstValue("Authorization")).contains("Bearer k1");

        HttpRequest anthropic = HttpProviderProbe.request(ProviderType.ANTHROPIC, null, "k2");
        assertThat(anthropic.uri().toString()).isEqualTo("https://api.anthropic.com/v1/models");
        assertThat(anthropic.headers().firstValue("x-api-key")).contains("k2");
        assertThat(anthropic.headers().firstValue("anthropic-version")).contains("2023-06-01");

        HttpRequest gemini = HttpProviderProbe.request(ProviderType.GEMINI, null, null);
        assertThat(gemini.uri().toString()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/models");
        assertThat(gemini.headers().firstValue("x-goog-api-key")).isEmpty();
    }

    @Test
    void statusCodesMapToResults() {
        Instant now = Instant.parse("2026-11-13T00:00:00Z");
        assertThat(ConnectivityCheck.ofHttpStatus(200, 5, now).ok()).isTrue();
        assertThat(ConnectivityCheck.ofHttpStatus(403, 5, now).status()).isEqualTo("auth_failed");
        assertThat(ConnectivityCheck.ofHttpStatus(404, 5, now).status()).isEqualTo("error");
        assertThat(new ConnectivityCheck("error", null, 1, "x".repeat(800), now).message())
                .hasSize(500);
    }
}
