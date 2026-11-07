package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.runtime.stream.app.SseProperties;
import cn.refinex.meridian.runtime.web.ApiEnumConverterFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * 公开 API 的装配（T07）：SSE 配置，以及查询参数到生成的 DTO 枚举的转换。处理器、用例与 Redis Stream 读取入口都是组件，
 * 由组件扫描装配；API Key 认证、请求 ID、{@code traceparent} 与 problem+json 来自 {@code meridian-security} 的自动配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SseProperties.class)
public class ApiConfiguration implements WebFluxConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(new ApiEnumConverterFactory());
    }
}
