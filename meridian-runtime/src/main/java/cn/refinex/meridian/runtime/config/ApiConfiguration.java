package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.runtime.stream.app.SseProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 公开 API 的装配（T07）：SSE 配置。处理器、用例与 Redis Stream 读取入口都是组件，由组件扫描装配；
 * API Key 认证、请求 ID、{@code traceparent} 与 problem+json 来自 {@code meridian-security} 的自动配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SseProperties.class)
public class ApiConfiguration {}
