package cn.refinex.meridian.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** {@link TraceParentWebFilter} 与 {@link TraceParents}。 */
class TraceParentWebFilterTest {

    private static final String VALID = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void validTraceparentIsEchoedAndItsTraceIdEntersTheContext() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/sessions").header(TraceParents.HEADER, VALID));
        AtomicReference<String> traceId = new AtomicReference<>();

        StepVerifier.create(new TraceParentWebFilter()
                        .filter(
                                exchange,
                                ex -> Mono.deferContextual(view -> {
                                    traceId.set(view.get(TraceParents.MDC_KEY));
                                    return Mono.empty();
                                })))
                .verifyComplete();

        assertThat(traceId.get()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceParents.HEADER))
                .isEqualTo(VALID);
    }

    @Test
    void invalidTraceparentIsIgnored() {
        for (String header : new String[] {
            "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
            "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
            "garbage"
        }) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/v1/sessions").header(TraceParents.HEADER, header));
            StepVerifier.create(new TraceParentWebFilter().filter(exchange, ex -> Mono.empty()))
                    .verifyComplete();
            assertThat(exchange.getResponse().getHeaders().containsHeader(TraceParents.HEADER))
                    .as(header)
                    .isFalse();
        }
    }
}
