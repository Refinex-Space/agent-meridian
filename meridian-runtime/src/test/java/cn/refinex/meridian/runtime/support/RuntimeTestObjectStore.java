package cn.refinex.meridian.runtime.support;

import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.persistence.objectstore.ObjectStoreProperties;
import cn.refinex.meridian.testsupport.MeridianContainers;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import java.net.URI;
import org.testcontainers.containers.MinIOContainer;

/** runtime 集成测试连接的对象存储（Testcontainers 单例 MinIO），同一个 JVM 内共用一个客户端。 */
public final class RuntimeTestObjectStore {

    private static ObjectStore store;

    private RuntimeTestObjectStore() {}

    /**
     * 对象存储客户端，桶为测试桶。
     *
     * @return 客户端
     */
    public static synchronized ObjectStore get() {
        if (store == null) {
            MinIOContainer minio = MeridianContainers.minio();
            store = ObjectStore.create(new ObjectStoreProperties(
                    URI.create(minio.getS3URL()),
                    SpringContainerProperties.MINIO_REGION,
                    MeridianContainers.MINIO_BUCKET,
                    minio.getUserName(),
                    minio.getPassword(),
                    true));
        }
        return store;
    }
}
