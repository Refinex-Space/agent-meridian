package cn.refinex.meridian.persistence.redis;

import cn.refinex.meridian.persistence.outbox.OutboxPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 客户端的自动配置：在 Spring Boot 的 Redis 模板之上创建阻塞与响应式的薄封装，以及 outbox 的默认发布器。
 */
@AutoConfiguration(after = {DataRedisAutoConfiguration.class, DataRedisReactiveAutoConfiguration.class})
public class RedisMessagingAutoConfiguration {

    /**
     * Pub/Sub 订阅容器，随 Spring 容器启停。
     *
     * @param connectionFactory Redis 连接工厂
     * @return 订阅容器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RedisConnectionFactory.class)
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    /**
     * 阻塞客户端。
     *
     * @param template 字符串 Redis 模板
     * @param listenerContainer 订阅容器
     * @return 阻塞客户端
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({StringRedisTemplate.class, RedisMessageListenerContainer.class})
    public RedisMessaging redisMessaging(
            StringRedisTemplate template, RedisMessageListenerContainer listenerContainer) {
        return new RedisMessaging(template, listenerContainer);
    }

    /**
     * 响应式客户端。
     *
     * @param template 响应式字符串 Redis 模板
     * @return 响应式客户端
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReactiveStringRedisTemplate.class)
    public ReactiveRedisMessaging reactiveRedisMessaging(ReactiveStringRedisTemplate template) {
        return new ReactiveRedisMessaging(template);
    }

    /**
     * outbox 的默认发布器：发布到 Redis Stream。
     *
     * @param messaging 阻塞客户端
     * @return 发布器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RedisMessaging.class)
    public OutboxPublisher redisStreamOutboxPublisher(RedisMessaging messaging) {
        return new RedisStreamOutboxPublisher(messaging);
    }
}
