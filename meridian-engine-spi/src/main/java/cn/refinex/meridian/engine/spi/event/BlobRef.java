package cn.refinex.meridian.engine.spi.event;

import java.util.Objects;

/**
 * 存在对象存储中的大对象引用，例如超过 16 KB 的工具结果（架构设计「事件映射」），经 {@code BlobStorePort} 写入与读取。
 *
 * @param key 对象键，由 runtime 按租户与会话生成，调用方不解析其结构；必填
 * @param contentType 媒体类型，例如 {@code text/plain; charset=utf-8}；必填
 * @param size 内容长度，单位字节，不小于 0
 */
public record BlobRef(String key, String contentType, long size) {

    /**
     * 创建大对象引用。
     *
     * @throws NullPointerException 缺少对象键或媒体类型时
     * @throws IllegalArgumentException 长度为负时
     */
    public BlobRef {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(contentType, "contentType");
        if (size < 0) {
            throw new IllegalArgumentException("size 不能为负：" + size);
        }
    }
}
