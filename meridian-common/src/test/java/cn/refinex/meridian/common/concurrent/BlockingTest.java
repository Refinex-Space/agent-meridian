package cn.refinex.meridian.common.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class BlockingTest {

    private static final PlatformContext CONTEXT =
            PlatformContext.builder().tenantId("ten_A").projectId("prj_A").build();

    @Test
    void runsOnVirtualThreadWithPlatformContextBound() {
        Mono<String> result = Mono.just("x")
                .publishOn(Schedulers.parallel())
                .flatMap(ignored -> Blocking.call(() -> Thread.currentThread().isVirtual()
                        + ":" + Thread.currentThread().getName().startsWith("meridian-blocking-")
                        + ":" + PlatformContexts.current().tenantId()))
                .contextWrite(PlatformContexts.write(CONTEXT));

        StepVerifier.create(result).expectNext("true:true:ten_A").verifyComplete();
    }

    @Test
    void worksWithoutPlatformContext() {
        StepVerifier.create(Blocking.call(() -> PlatformContexts.find().isPresent()))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void isLazyAndPropagatesErrors() {
        AtomicBoolean executed = new AtomicBoolean();
        Mono<Void> mono = Blocking.run(() -> {
            executed.set(true);
            throw new IOException("磁盘错误");
        });

        assertThat(executed).isFalse();
        StepVerifier.create(mono).expectErrorMessage("磁盘错误").verify();
        assertThat(executed).isTrue();
    }

    @Test
    void nullResultCompletesEmpty() {
        StepVerifier.create(Blocking.call(() -> null)).verifyComplete();
    }
}
