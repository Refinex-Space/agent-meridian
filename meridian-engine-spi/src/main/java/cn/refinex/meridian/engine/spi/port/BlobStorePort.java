package cn.refinex.meridian.engine.spi.port;

import cn.refinex.meridian.engine.spi.event.BlobRef;
import reactor.core.publisher.Mono;

/**
 * 大对象的写入与读取，例如超过 16 KB 的工具结果，由 runtime 的 {@code stream} 实现（对象存储 {@code rt/} 前缀），
 * 见架构设计「端口」「事件映射」。
 *
 * <p>对象键由实现按 Reactor Context 中 {@code PlatformContext} 的租户与会话生成，会话删除时一并清除。
 */
public interface BlobStorePort {

    /** 工具结果内联在事件中的上限，单位字节。架构设计「事件映射」规定超过 16 KB 的结果存对象存储。 */
    int INLINE_LIMIT_BYTES = 16 * 1024;

    /**
     * 写入一个大对象。
     *
     * @param contentType 媒体类型
     * @param content 内容
     * @return 对象引用
     */
    Mono<BlobRef> put(String contentType, byte[] content);

    /**
     * 读取一个大对象。
     *
     * @param ref 对象引用，必须属于调用方的会话
     * @return 内容；对象不存在时以 {@code NotFoundException} 失败
     */
    Mono<byte[]> get(BlobRef ref);
}
