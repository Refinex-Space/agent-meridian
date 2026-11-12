package cn.refinex.meridian.runtime.webhook.infra;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.runtime.webhook.app.WebhookSender;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Webhook 的 HTTP 发送（T17a，ADR-0049）：状态码、超时、地址策略（IP 字面量与解析结果都校验），不跟随重定向。 */
class HttpWebhookSenderTest {

    @RegisterExtension
    static final WireMockExtension receiver = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final HttpWebhookSender open =
            new HttpWebhookSender(new EndpointPolicy(true, List.of()), Duration.ofSeconds(2));
    private final HttpWebhookSender strict =
            new HttpWebhookSender(new EndpointPolicy(false, List.of()), Duration.ofSeconds(2));

    @AfterEach
    void close() {
        open.close();
        strict.close();
    }

    @Test
    void postsTheBodyWithHeadersAndReportsTheStatus() {
        receiver.stubFor(post(urlEqualTo("/ok")).willReturn(aResponse().withStatus(204)));
        receiver.stubFor(
                post(urlEqualTo("/fail")).willReturn(aResponse().withStatus(500).withBody("boom")));

        WebhookSender.Result ok =
                open.send(url("/ok"), Map.of("Meridian-Signature", "t=1,v1=ab"), "{\"id\":\"evt_1\"}");
        WebhookSender.Result failed = open.send(url("/fail"), Map.of(), "{}");

        assertThat(ok.succeeded()).isTrue();
        assertThat(ok.status()).isEqualTo(204);
        assertThat(ok.error()).isNull();
        assertThat(failed.succeeded()).isFalse();
        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.error()).contains("500");
        receiver.verify(postRequestedFor(urlEqualTo("/ok"))
                .withHeader("Meridian-Signature", equalTo("t=1,v1=ab"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalTo("{\"id\":\"evt_1\"}")));
    }

    @Test
    void redirectsAreNotFollowed() {
        receiver.stubFor(post(urlEqualTo("/moved"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", url("/ok"))));

        WebhookSender.Result result = open.send(url("/moved"), Map.of(), "{}");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.status()).isEqualTo(302);
        receiver.verify(0, postRequestedFor(urlEqualTo("/ok")));
    }

    @Test
    void slowReceiversTimeOut() {
        receiver.stubFor(
                post(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(5_000)));

        long start = System.nanoTime();
        WebhookSender.Result result = open.send(url("/slow"), Map.of(), "{}");

        assertThat(result.status()).isNull();
        assertThat(result.error()).isNotNull();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void disallowedAddressesAreRejectedBeforeAndAfterResolution() {
        receiver.stubFor(post(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)));

        WebhookSender.Result literal = strict.send(url("/ok"), Map.of(), "{}");
        WebhookSender.Result resolved = strict.send("http://localhost:" + receiver.getPort() + "/ok", Map.of(), "{}");
        WebhookSender.Result scheme = open.send("ftp://127.0.0.1/ok", Map.of(), "{}");

        assertThat(literal.status()).isNull();
        assertThat(literal.error()).contains("not allowed");
        assertThat(resolved.status()).isNull();
        assertThat(resolved.error()).contains("not allowed");
        assertThat(scheme.error()).contains("not allowed");
        receiver.verify(0, postRequestedFor(urlEqualTo("/ok")));
    }

    private static String url(String path) {
        return "http://127.0.0.1:" + receiver.getPort() + path;
    }
}
