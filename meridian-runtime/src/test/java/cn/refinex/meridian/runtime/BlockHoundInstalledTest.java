package cn.refinex.meridian.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * T05-A08：runtime 的测试 JVM 由 {@code blockhound-junit-platform} 自动安装 BlockHound，本测试没有自己安装，
 * Reactor 非阻塞线程上的阻塞调用仍被拦截。
 */
class BlockHoundInstalledTest {

    @Test
    void blockingOnAReactorNonBlockingThreadFailsWithoutExplicitInstall() {
        Mono<Object> sleeping = Mono.fromCallable(() -> {
                    Thread.sleep(1);
                    return new Object();
                })
                .subscribeOn(Schedulers.parallel());

        assertThatThrownBy(sleeping::block).hasCauseInstanceOf(BlockingOperationError.class);
    }
}
