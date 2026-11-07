package cn.refinex.meridian.testsupport;

import java.io.IOException;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试共用的容器单例：PostgreSQL（pgvector + zhparser）、Redis、MinIO。
 *
 * <p>每个容器在第一次被访问时启动，同一个测试 JVM 内的所有测试类共用，测试结束不停止，
 * JVM 退出后由 Testcontainers 的 Ryuk 回收。共用意味着数据会跨测试类保留：
 * 测试用随机 ID 隔离数据，或在开始前用 {@link TestDatabase#truncate(String)} 清空自己用到的 schema。
 *
 * <p>镜像版本与 {@code deploy/compose/compose.yaml} 保持一致，修改时两处一起改。
 */
public final class MeridianContainers {

    /** 数据库名，与本地 compose 一致。 */
    public static final String DATABASE = "meridian";

    /** 迁移角色：拥有 cp、rt、kb，执行 DDL。 */
    public static final String MIGRATOR_USER = "meridian_migrator";

    /** 迁移角色的测试口令，只用于测试容器。 */
    public static final String MIGRATOR_PASSWORD = "test-migrator";

    /** 应用角色：只有 DML。 */
    public static final String APP_USER = "meridian_app";

    /** 应用角色的测试口令，只用于测试容器。 */
    public static final String APP_PASSWORD = "test-app";

    /** MinIO 中预先创建的桶。 */
    public static final String MINIO_BUCKET = "meridian-test";

    /** Redis 镜像，与 compose 一致。 */
    static final String REDIS_IMAGE = "redis:7.4.11-alpine";

    /** MinIO 镜像：官方镜像已停止发布，使用 Chainguard 从源码构建的版本，按摘要固定，与 compose 一致。 */
    static final String MINIO_IMAGE =
            "chainguard/minio@sha256:869ca3178831d9f521ea02258a0f0d4fbc1e9f84302e707514e1f861b860865d";

    private MeridianContainers() {}

    /**
     * 返回已启动的 PostgreSQL 容器。超级用户口令由容器决定；业务测试应使用 {@link TestDatabase} 提供的两个角色。
     *
     * @return PostgreSQL 容器
     */
    public static PostgreSQLContainer postgres() {
        return PostgresHolder.INSTANCE;
    }

    /**
     * 返回已启动的 Redis 容器，端口 6379 已映射。
     *
     * @return Redis 容器
     */
    public static GenericContainer<?> redis() {
        return RedisHolder.INSTANCE;
    }

    /**
     * 返回已启动的 MinIO 容器，桶 {@link #MINIO_BUCKET} 已创建。
     *
     * @return MinIO 容器
     */
    public static MinIOContainer minio() {
        return MinioHolder.INSTANCE;
    }

    /** 持有者类：首次访问时才初始化，由类加载机制保证只启动一次且线程安全。 */
    private static final class PostgresHolder {
        static final PostgreSQLContainer INSTANCE = start();

        private static PostgreSQLContainer start() {
            PostgreSQLContainer container = new PostgreSQLContainer(PostgresImage.resolve())
                    .withDatabaseName(DATABASE)
                    .withEnv("MERIDIAN_MIGRATOR_PASSWORD", MIGRATOR_PASSWORD)
                    .withEnv("MERIDIAN_APP_PASSWORD", APP_PASSWORD);
            container.start();
            return container;
        }
    }

    private static final class RedisHolder {
        static final GenericContainer<?> INSTANCE = start();

        private static GenericContainer<?> start() {
            GenericContainer<?> container =
                    new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);
            container.start();
            return container;
        }
    }

    private static final class MinioHolder {
        static final MinIOContainer INSTANCE = start();

        private static MinIOContainer start() {
            MinIOContainer container =
                    new MinIOContainer(DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"));
            container.start();
            createBucket(container);
            return container;
        }

        private static void createBucket(MinIOContainer container) {
            String alias = "mc alias set local http://localhost:9000 " + container.getUserName() + " "
                    + container.getPassword();
            String script = alias + " && mc mb --ignore-existing local/" + MINIO_BUCKET;
            try {
                ExecResult result = container.execInContainer("sh", "-c", script);
                if (result.getExitCode() != 0) {
                    throw new IllegalStateException("创建 MinIO 桶失败：" + result.getStderr());
                }
            } catch (IOException e) {
                throw new IllegalStateException("创建 MinIO 桶失败", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("创建 MinIO 桶时被中断", e);
            }
        }
    }
}
