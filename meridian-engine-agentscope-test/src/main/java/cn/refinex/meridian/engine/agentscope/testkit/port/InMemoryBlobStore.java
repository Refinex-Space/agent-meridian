package cn.refinex.meridian.engine.agentscope.testkit.port;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.spi.event.BlobRef;
import cn.refinex.meridian.engine.spi.port.BlobStorePort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;

/**
 * {@link BlobStorePort} 的内存实现：对象键按写入顺序编号。
 *
 * <p>线程安全：用并发映射保存。
 */
public final class InMemoryBlobStore implements BlobStorePort {

    private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public Mono<BlobRef> put(String contentType, byte[] content) {
        return Mono.fromSupplier(() -> {
            String key = "blob/" + sequence.incrementAndGet();
            blobs.put(key, content.clone());
            return new BlobRef(key, contentType, content.length);
        });
    }

    @Override
    public Mono<byte[]> get(BlobRef ref) {
        return Mono.defer(() -> {
            byte[] content = blobs.get(ref.key());
            return content == null
                    ? Mono.error(new NotFoundException("大对象不存在：" + ref.key()))
                    : Mono.just(content.clone());
        });
    }

    /**
     * 已写入的对象数。
     *
     * @return 对象数
     */
    public int size() {
        return blobs.size();
    }
}
