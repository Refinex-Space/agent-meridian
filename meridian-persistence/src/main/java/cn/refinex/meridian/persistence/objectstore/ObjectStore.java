package cn.refinex.meridian.persistence.objectstore;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 兼容对象存储客户端（AWS SDK v2，兼容 MinIO、OSS、COS），操作一个固定的桶：上传、下载、删除、按前缀删除、
 * 预签名下载 URL。存放大工具结果、上传文件、产物、沙箱快照、归档事件（架构设计「存储分工」）。
 *
 * <p>所有方法都是阻塞调用，runtime 中经 {@code common.concurrent.Blocking} 调度。
 *
 * <p>请求与响应的校验和只在服务端要求时计算：SDK 默认附加的 CRC 校验头不被部分 S3 兼容存储接受。
 *
 * <p>线程安全：可共享；用完调用 {@link #close()} 释放连接池（作为 Spring Bean 时自动关闭）。
 */
public final class ObjectStore implements AutoCloseable {

    /** DeleteObjects 一次最多删除的对象数，S3 API 的上限。 */
    private static final int DELETE_BATCH_SIZE = 1000;

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    private ObjectStore(S3Client client, S3Presigner presigner, String bucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    /**
     * 按配置创建客户端。
     *
     * @param properties 连接配置
     * @return 客户端
     */
    public static ObjectStore create(ObjectStoreProperties properties) {
        AwsCredentialsProvider credentials = properties.accessKey() == null
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(properties.pathStyleAccess())
                .build();
        Region region = Region.of(properties.region());

        S3ClientBuilder clientBuilder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Configuration)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Configuration);
        if (properties.endpoint() != null) {
            clientBuilder.endpointOverride(properties.endpoint());
            presignerBuilder.endpointOverride(properties.endpoint());
        }
        return new ObjectStore(clientBuilder.build(), presignerBuilder.build(), properties.bucket());
    }

    /**
     * 返回本客户端操作的桶。
     *
     * @return 桶名
     */
    public String bucket() {
        return bucket;
    }

    /**
     * 上传一个对象，已存在时覆盖。
     *
     * @param key 对象键
     * @param content 内容
     * @param contentType MIME 类型，例如 {@code application/json}
     */
    public void put(String key, byte[] content, String contentType) {
        client.putObject(r -> r.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
    }

    /**
     * 以流的方式上传一个对象，已存在时覆盖。流由调用方关闭。
     *
     * @param key 对象键
     * @param content 内容流
     * @param length 内容长度（字节），必须准确
     * @param contentType MIME 类型
     */
    public void put(String key, InputStream content, long length, String contentType) {
        client.putObject(
                r -> r.bucket(bucket).key(key).contentType(contentType).contentLength(length),
                RequestBody.fromInputStream(content, length));
    }

    /**
     * 下载一个对象的全部内容，适合小对象；大对象用 {@link #open(String)}。
     *
     * @param key 对象键
     * @return 内容，对象不存在时为空
     */
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(
                    client.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /**
     * 以流的方式下载一个对象。调用方必须关闭返回的流，否则连接不会归还连接池。
     *
     * @param key 对象键
     * @return 内容流，对象不存在时为空
     */
    public Optional<InputStream> open(String key) {
        try {
            return Optional.of(client.getObject(r -> r.bucket(bucket).key(key)));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /**
     * 删除一个对象；对象不存在时也视为成功。
     *
     * @param key 对象键
     */
    public void delete(String key) {
        client.deleteObject(r -> r.bucket(bucket).key(key));
    }

    /**
     * 删除键以指定前缀开头的全部对象，例如删除会话时清理 {@code tenants/ten_x/sessions/ses_y/} 下的产物。
     *
     * @param prefix 键前缀，不能为空白：空前缀会删除整个桶
     * @return 删除的对象数
     * @throws IllegalArgumentException 前缀为空白时
     * @throws IllegalStateException 有对象删除失败时，消息中列出第一个失败的键
     */
    public int deleteByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("deleteByPrefix 的前缀不能为空白，否则会删除整个桶");
        }
        ListObjectsV2Request request =
                ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build();
        List<ObjectIdentifier> keys = client.listObjectsV2Paginator(request).contents().stream()
                .map(S3Object::key)
                .map(key -> ObjectIdentifier.builder().key(key).build())
                .toList();
        for (int from = 0; from < keys.size(); from += DELETE_BATCH_SIZE) {
            List<ObjectIdentifier> batch = keys.subList(from, Math.min(from + DELETE_BATCH_SIZE, keys.size()));
            DeleteObjectsResponse response = client.deleteObjects(r -> r.bucket(bucket)
                    .delete(Delete.builder().objects(batch).quiet(true).build()));
            if (response.hasErrors() && !response.errors().isEmpty()) {
                throw new IllegalStateException(
                        "删除对象失败：" + response.errors().getFirst().key() + "（"
                                + response.errors().getFirst().code() + "）");
            }
        }
        return keys.size();
    }

    /**
     * 生成一个预签名的下载 URL，持有者在有效期内无需凭据即可下载该对象。
     *
     * @param key 对象键
     * @param ttl 有效期，S3 上限为 7 天
     * @return 预签名 URL
     */
    public URL presignGet(String key, Duration ttl) {
        return presigner
                .presignGetObject(r -> r.signatureDuration(ttl)
                        .getObjectRequest(g -> g.bucket(bucket).key(key)))
                .url();
    }

    /** 关闭底层 HTTP 客户端与预签名器。 */
    @Override
    public void close() {
        presigner.close();
        client.close();
    }
}
