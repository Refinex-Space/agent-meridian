package cn.refinex.meridian.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MinIOContainer;

/**
 * 为 Spring 测试注册容器的连接属性，在测试类的 {@code @DynamicPropertySource} 方法中调用：
 *
 * <pre>{@code
 * @DynamicPropertySource
 * static void containers(DynamicPropertyRegistry registry) {
 *     SpringContainerProperties.postgres(registry);
 *     SpringContainerProperties.redis(registry);
 * }
 * }</pre>
 *
 * <p>属性用 Supplier 注册，容器在 Spring 读取属性时才启动。
 */
public final class SpringContainerProperties {

    /** 测试中 S3 客户端使用的区域；MinIO 不校验区域，但 AWS SDK 要求设置。 */
    public static final String MINIO_REGION = "us-east-1";

    private SpringContainerProperties() {}

    /**
     * 注册数据源与 Flyway 属性：应用以 {@code meridian_app} 连接，Flyway 以 {@code meridian_migrator} 执行迁移，
     * 与生产的角色划分一致（编码规范「数据访问」）。
     *
     * @param registry Spring 的动态属性注册表
     */
    public static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> MeridianContainers.APP_USER);
        registry.add("spring.datasource.password", () -> MeridianContainers.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> MeridianContainers.MIGRATOR_USER);
        registry.add("spring.flyway.password", () -> MeridianContainers.MIGRATOR_PASSWORD);
    }

    /**
     * 注册 Redis 连接属性。
     *
     * @param registry Spring 的动态属性注册表
     */
    public static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> MeridianContainers.redis().getHost());
        registry.add("spring.data.redis.port", () -> MeridianContainers.redis().getMappedPort(6379));
    }

    /**
     * 注册对象存储属性（前缀 {@code meridian.persistence.objectstore}），指向 MinIO 中预建的桶。
     *
     * @param registry Spring 的动态属性注册表
     */
    public static void minio(DynamicPropertyRegistry registry) {
        String prefix = "meridian.persistence.objectstore.";
        registry.add(prefix + "endpoint", () -> minio().getS3URL());
        registry.add(prefix + "region", () -> MINIO_REGION);
        registry.add(prefix + "bucket", () -> MeridianContainers.MINIO_BUCKET);
        registry.add(prefix + "access-key", () -> minio().getUserName());
        registry.add(prefix + "secret-key", () -> minio().getPassword());
        registry.add(prefix + "path-style-access", () -> true);
    }

    private static MinIOContainer minio() {
        return MeridianContainers.minio();
    }
}
