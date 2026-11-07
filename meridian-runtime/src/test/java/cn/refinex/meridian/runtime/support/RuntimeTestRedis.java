package cn.refinex.meridian.runtime.support;

import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.testsupport.MeridianContainers;
import java.util.List;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/** runtime 集成测试连接的 Redis（Testcontainers 单例容器），同一个 JVM 内共用一个连接工厂。 */
public final class RuntimeTestRedis {

    private static LettuceConnectionFactory factory;
    private static ReactiveRedisMessaging messaging;

    private RuntimeTestRedis() {}

    /**
     * 响应式 Redis 消息封装。
     *
     * @return 封装
     */
    public static synchronized ReactiveRedisMessaging reactiveMessaging() {
        if (messaging == null) {
            messaging = new ReactiveRedisMessaging(new ReactiveStringRedisTemplate(factory()));
        }
        return messaging;
    }

    /**
     * 读取一个 Stream 的全部条目，按写入顺序。
     *
     * @param stream Stream 的键
     * @return 条目
     */
    public static List<MapRecord<String, Object, Object>> readAll(String stream) {
        return new StringRedisTemplate(factory()).opsForStream().range(stream, Range.unbounded());
    }

    private static LettuceConnectionFactory factory() {
        if (factory == null) {
            factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                    MeridianContainers.redis().getHost(),
                    MeridianContainers.redis().getMappedPort(6379)));
            factory.afterPropertiesSet();
            factory.start();
        }
        return factory;
    }
}
