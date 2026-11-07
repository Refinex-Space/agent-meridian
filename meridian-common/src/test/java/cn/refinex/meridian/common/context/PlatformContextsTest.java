package cn.refinex.meridian.common.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PlatformContextsTest {

    private static final PlatformContext CONTEXT = PlatformContext.builder()
            .tenantId("ten_A")
            .projectId("prj_A")
            .interaction(Interaction.ATTENDED)
            .build();

    @Test
    void scopedBindingIsVisibleOnlyInsideTheScope() throws Exception {
        assertThat(PlatformContexts.find()).isEmpty();

        String tenant =
                PlatformContexts.call(CONTEXT, () -> PlatformContexts.current().tenantId());
        AtomicReference<String> project = new AtomicReference<>();
        PlatformContexts.run(
                CONTEXT, () -> project.set(PlatformContexts.current().projectId()));

        assertThat(tenant).isEqualTo("ten_A");
        assertThat(project).hasValue("prj_A");
        assertThat(PlatformContexts.find()).isEmpty();
    }

    @Test
    void nestedBindingShadowsOuterOne() throws Exception {
        PlatformContext turn = CONTEXT.toBuilder()
                .threadId("thr_1")
                .turnId("trn_1")
                .leaseEpoch(3L)
                .build();

        String observed = PlatformContexts.call(
                CONTEXT,
                () -> PlatformContexts.call(
                                turn, () -> PlatformContexts.current().turnId()) + "/"
                        + PlatformContexts.current().turnId());

        assertThat(observed).isEqualTo("trn_1/null");
    }

    @Test
    void missingScopedContextFailsLoudly() {
        assertThatThrownBy(PlatformContexts::current).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reactorContextRoundTrips() {
        StepVerifier.create(PlatformContexts.currentReactive().contextWrite(PlatformContexts.write(CONTEXT)))
                .expectNext(CONTEXT)
                .verifyComplete();
        StepVerifier.create(PlatformContexts.currentReactive())
                .expectError(IllegalStateException.class)
                .verify();
        StepVerifier.create(Mono.deferContextual(view -> Mono.justOrEmpty(PlatformContexts.find(view)))
                        .contextWrite(PlatformContexts.write(CONTEXT)))
                .expectNext(CONTEXT)
                .verifyComplete();
    }

    @Test
    void tenantIsRequired() {
        assertThatThrownBy(() -> PlatformContext.builder().projectId("prj_A").build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
