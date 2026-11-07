package cn.refinex.meridian.runtime.stream.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.spi.event.BlobRef;
import cn.refinex.meridian.runtime.support.RuntimeTestObjectStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** 大对象存储（{@code BlobStorePort} 的对象存储实现）：键按租户与会话分前缀，只能读自己会话的对象。 */
class ObjectStoreBlobStoreIT {

    private final ObjectStoreBlobStore blobs = new ObjectStoreBlobStore(RuntimeTestObjectStore.get());
    private final PlatformContext session = PlatformContext.builder()
            .tenantId("ten_blob")
            .projectId("prj_blob")
            .sessionId("ses_blob")
            .build();

    @Test
    void largeResultsRoundTripUnderTheSessionPrefix() {
        byte[] content = "大工具结果".repeat(5000).getBytes(StandardCharsets.UTF_8);

        BlobRef ref = in(session, blobs.put("text/plain", content));

        assertThat(ref.key()).startsWith(ObjectStoreBlobStore.sessionPrefix("ten_blob", "ses_blob") + "blobs/");
        assertThat(ref.size()).isEqualTo(content.length);
        assertThat(in(session, blobs.get(ref))).isEqualTo(content);
    }

    @Test
    void otherSessionsAndMissingObjectsAreNotFound() {
        BlobRef ref = in(session, blobs.put("text/plain", "x".getBytes(StandardCharsets.UTF_8)));
        PlatformContext other = session.toBuilder().sessionId("ses_other").build();

        assertThatThrownBy(() -> in(other, blobs.get(ref))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> in(session, blobs.get(new BlobRef(ref.key() + "-missing", "text/plain", 1))))
                .isInstanceOf(NotFoundException.class);
    }

    private static <T> T in(PlatformContext context, Mono<T> mono) {
        return mono.contextWrite(PlatformContexts.write(context)).block(Duration.ofSeconds(30));
    }
}
