package cn.refinex.meridian.persistence.redis;

import cn.refinex.meridian.testsupport.MeridianContainers;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 连接测试容器中 Redis 的客户端，同一个 JVM 内共用。 */
public final class RedisFixtures {

    private static LettuceConnectionFactory factory;
    private static RedisMessaging messaging;
    private static ReactiveRedisMessaging reactiveMessaging;

    private RedisFixtures() {}

    public static synchronized RedisMessaging messaging() {
        if (messaging == null) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(factory());
            container.afterPropertiesSet();
            container.start();
            messaging = new RedisMessaging(new StringRedisTemplate(factory()), container);
        }
        return messaging;
    }

    public static synchronized ReactiveRedisMessaging reactiveMessaging() {
        if (reactiveMessaging == null) {
            reactiveMessaging = new ReactiveRedisMessaging(new ReactiveStringRedisTemplate(factory()));
        }
        return reactiveMessaging;
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
