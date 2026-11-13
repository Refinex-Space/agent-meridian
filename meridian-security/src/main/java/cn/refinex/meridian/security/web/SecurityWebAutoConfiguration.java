package cn.refinex.meridian.security.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 入口公共能力的自动配置：请求 ID、{@code traceparent}（WebFlux）与 problem+json，按应用的 Web 栈装配对应的适配器。
 */
@AutoConfiguration
public class SecurityWebAutoConfiguration {

    /**
     * 共用的 problem+json 映射逻辑。
     *
     * @return 映射器
     */
    @Bean
    @ConditionalOnMissingBean
    public ProblemMapper problemMapper() {
        return new ProblemMapper();
    }

    /** MVC 服务（control-plane、knowledge）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class Servlet {

        @Bean
        RequestIdFilter requestIdFilter() {
            return new RequestIdFilter();
        }

        @Bean
        MvcProblemHandler mvcProblemHandler(ProblemMapper mapper) {
            return new MvcProblemHandler(mapper);
        }

        /** 查询参数按规范取值转换为生成的 DTO 枚举（T19b；runtime 在自己的 WebFlux 配置中注册同一个转换器）。 */
        @Bean
        WebMvcConfigurer apiEnumConversion() {
            return new WebMvcConfigurer() {
                @Override
                public void addFormatters(FormatterRegistry registry) {
                    registry.addConverterFactory(new ApiEnumConverterFactory());
                }
            };
        }
    }

    /** WebFlux 服务（runtime）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class Reactive {

        @Bean
        RequestIdWebFilter requestIdWebFilter() {
            return new RequestIdWebFilter();
        }

        @Bean
        TraceParentWebFilter traceParentWebFilter() {
            return new TraceParentWebFilter();
        }

        @Bean
        WebFluxProblemHandler webFluxProblemHandler(ProblemMapper mapper) {
            return new WebFluxProblemHandler(mapper);
        }
    }

    /** 有 context-propagation 时，注册 MDC 访问器，让 WebFlux 日志带上请求 ID。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = "io.micrometer.context.ContextRegistry")
    static class ReactiveMdc {

        ReactiveMdc() {
            RequestIdMdcAccessor.register();
            TraceIdMdcAccessor.register();
        }
    }
}
