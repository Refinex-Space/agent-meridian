package cn.refinex.meridian.runtime.file.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 文件的配置（{@code meridian.runtime.files}，ADR-0050）。
 *
 * @param maxSize 上传文件与工作区写入的大小上限，默认 32 MB；{@code application.yml} 把它同时设为
 *     {@code spring.webflux.multipart.max-disk-usage-per-part}，表单解析阶段就截断超大的上传
 */
@ConfigurationProperties("meridian.runtime.files")
public record FileProperties(@DefaultValue("32MB") DataSize maxSize) {

    /**
     * 校验。
     *
     * @throws IllegalArgumentException 上限不大于 0 时
     */
    public FileProperties {
        if (maxSize.toBytes() <= 0) {
            throw new IllegalArgumentException("meridian.runtime.files.max-size 必须大于 0");
        }
    }
}
