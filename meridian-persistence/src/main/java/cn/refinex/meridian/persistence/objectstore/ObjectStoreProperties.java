package cn.refinex.meridian.persistence.objectstore;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 兼容对象存储的连接配置，前缀 {@code meridian.persistence.objectstore}。端点与凭据只来自环境变量或本地配置
 * （编码规范「配置」）。
 *
 * @param endpoint S3 端点，例如本地 MinIO 的 {@code http://localhost:19000}；可为空，表示 AWS S3 的默认端点
 * @param region 区域；MinIO 不校验，但 SDK 要求设置
 * @param bucket 平台使用的桶，必填
 * @param accessKey 访问密钥 ID；可为空，表示使用 SDK 默认的凭据链（环境变量、实例角色等）
 * @param secretKey 访问密钥；与 {@code accessKey} 同时设置
 * @param pathStyleAccess 是否使用 path-style 地址（{@code endpoint/bucket/key}）；MinIO 需要 {@code true}
 */
@ConfigurationProperties("meridian.persistence.objectstore")
public record ObjectStoreProperties(
        URI endpoint, String region, String bucket, String accessKey, String secretKey, boolean pathStyleAccess) {

    /**
     * 校验配置。
     *
     * @throws IllegalArgumentException 缺少区域、桶，或只设置了一半的静态凭据时
     */
    public ObjectStoreProperties {
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("meridian.persistence.objectstore.region 不能为空");
        }
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("meridian.persistence.objectstore.bucket 不能为空");
        }
        if ((accessKey == null) != (secretKey == null)) {
            throw new IllegalArgumentException("access-key 与 secret-key 必须同时设置或同时为空");
        }
    }

    /** 不输出密钥，防止配置被打印到日志时泄露凭据。 */
    @Override
    public String toString() {
        return "ObjectStoreProperties[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket
                + ", pathStyleAccess=" + pathStyleAccess + "]";
    }
}
