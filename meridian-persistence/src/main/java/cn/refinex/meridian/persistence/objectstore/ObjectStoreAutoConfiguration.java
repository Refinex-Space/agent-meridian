package cn.refinex.meridian.persistence.objectstore;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 对象存储客户端的自动配置，设置了 {@code meridian.persistence.objectstore.bucket} 时生效。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "meridian.persistence.objectstore", name = "bucket")
@EnableConfigurationProperties(ObjectStoreProperties.class)
public class ObjectStoreAutoConfiguration {

    /**
     * 对象存储客户端，容器关闭时释放连接池。
     *
     * @param properties 连接配置
     * @return 客户端
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ObjectStore objectStore(ObjectStoreProperties properties) {
        return ObjectStore.create(properties);
    }
}
