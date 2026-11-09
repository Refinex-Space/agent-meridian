package cn.refinex.meridian.security.rbac;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 声明式授权的自动配置：MVC 应用提供了 {@link GrantSource}（control-plane）时注册 {@link PermissionInterceptor}。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.config.annotation.WebMvcConfigurer")
public class RbacAutoConfiguration {

    /**
     * 注册拦截器。
     *
     * @param grants 角色绑定的来源
     * @return MVC 配置
     */
    @Bean
    @ConditionalOnBean(GrantSource.class)
    public WebMvcConfigurer permissionInterceptorConfigurer(GrantSource grants) {
        PermissionInterceptor interceptor = new PermissionInterceptor(grants);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
