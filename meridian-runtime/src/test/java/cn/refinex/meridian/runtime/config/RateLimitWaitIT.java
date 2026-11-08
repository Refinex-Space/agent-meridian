package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelRoute;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 限流等待（T09a-A02 的"超过 RPM 时请求等待"）：每分钟 60 次的令牌桶用完后，下一次请求按令牌桶给出的时间等约 1 秒再放行，而不是失败。
 * 等待超过 30 秒的情况见 {@code ModelRoutingWorkerIT}。
 */
class RateLimitWaitIT {

    @TempDir
    Path directory;

    private ModelRoutingFixture routing;

    @AfterEach
    void close() {
        routing.close();
    }

    @Test
    void requestsBeyondRpmWaitForTheBucketToRefill() {
        routing = new ModelRoutingFixture(directory, "http://provider.invalid/v1")
                .model("mdl_wait", "up", 32_000, 100)
                .rateLimit(60, 0);
        ModelRoute route = routing.gateway
                .resolve("mdl_wait")
                .contextWrite(PlatformContexts.write(routing.context("ses_" + Ids.newUlid())))
                .block(Duration.ofSeconds(5));
        for (int i = 0; i < 60; i++) {
            routing.gateway.awaitRateLimit(route, 100).block(Duration.ofSeconds(5));
        }

        long start = System.nanoTime();
        routing.gateway.awaitRateLimit(route, 100).block(Duration.ofSeconds(10));
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(waited)
                .as("桶容量 60、每秒补 1 个：第 61 次请求等到补满一个再放行")
                .isBetween(Duration.ofMillis(500), Duration.ofSeconds(5));
    }
}
