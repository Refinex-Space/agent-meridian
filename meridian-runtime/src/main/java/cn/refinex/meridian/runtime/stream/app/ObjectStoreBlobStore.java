package cn.refinex.meridian.runtime.stream.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.event.BlobRef;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * {@link BlobStorePort} 的实现：大对象（超过 16 KB 的工具结果等）写入对象存储，事件与账本中只放引用（架构设计「端口」
 * 「事件映射」）。替换 T04 测试用的内存实现。
 *
 * <p>对象键为 {@code rt/{tenant_id}/{session_id}/blobs/{ulid}}，租户与会话取自 Reactor Context 中的 {@code PlatformContext}：
 * 删除会话时按前缀 {@code rt/{tenant_id}/{session_id}/} 一并清除（T17a）。读取只接受调用方会话前缀下的引用，其他引用一律按
 * 不存在处理，不泄露别的会话的对象是否存在。
 *
 * <p>对象存储的调用是阻塞的，经 {@link Blocking} 在虚拟线程上执行。线程安全：无状态。
 */
public final class ObjectStoreBlobStore implements BlobStorePort {

    private final ObjectStore objects;

    /**
     * 创建大对象存储。
     *
     * @param objects 对象存储客户端
     */
    public ObjectStoreBlobStore(ObjectStore objects) {
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    /**
     * 会话的大对象前缀，删除会话时按它清除。
     *
     * @param tenantId 租户 ID
     * @param sessionId 会话 ID
     * @return {@code rt/{tenant_id}/{session_id}/}
     */
    public static String sessionPrefix(String tenantId, String sessionId) {
        return "rt/" + tenantId + "/" + sessionId + "/";
    }

    @Override
    public Mono<BlobRef> put(String contentType, byte[] content) {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(content, "content");
        return PlatformContexts.currentReactive().flatMap(context -> {
            String key = prefix(context) + "blobs/" + Ids.newUlid();
            return Blocking.run(() -> objects.put(key, content, contentType))
                    .thenReturn(new BlobRef(key, contentType, content.length));
        });
    }

    @Override
    public Mono<byte[]> get(BlobRef ref) {
        Objects.requireNonNull(ref, "ref");
        return PlatformContexts.currentReactive().flatMap(context -> {
            if (!ref.key().startsWith(prefix(context))) {
                return Mono.error(new NotFoundException("大对象不存在：" + ref.key()));
            }
            return Blocking.call(
                    () -> objects.get(ref.key()).orElseThrow(() -> new NotFoundException("大对象不存在：" + ref.key())));
        });
    }

    private static String prefix(PlatformContext context) {
        if (context.tenantId() == null || context.sessionId() == null) {
            throw new IllegalStateException("读写大对象需要 PlatformContext 中的租户与会话");
        }
        return sessionPrefix(context.tenantId(), context.sessionId());
    }
}
