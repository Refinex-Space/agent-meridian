package cn.refinex.meridian.security.oidc;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 控制台用户认证的自动配置，只在 MVC 服务中生效（管理 API 在 control-plane）。
 *
 * <p>解析器的选择与 API Key 相同：应用提供了 {@link ConsoleUserResolver}（T14b 的 OIDC）时用它；否则启用了开发期令牌时用
 * {@link DevConsoleUserResolver}，它只允许在 {@code dev}、{@code test} profile 启用，其他 profile 中启用直接让启动失败；都没有时一律
 * 拒绝，管理 API 不会在没有身份来源时被放行。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.HandlerExceptionResolver")
@EnableConfigurationProperties(DevConsoleUserProperties.class)
public class ConsoleAuthAutoConfiguration {

    /** 允许启用开发期控制台令牌的 profile。 */
    static final String[] DEV_PROFILES = {"dev", "test"};

    /**
     * 控制台用户令牌的解析器。
     *
     * @param properties 开发期控制台用户配置
     * @param environment 环境，检查当前 profile
     * @return 开发期解析器，或拒绝一切的解析器
     * @throws IllegalStateException 在 {@code dev}、{@code test} 之外的 profile 启用开发期令牌时
     */
    @Bean
    @ConditionalOnMissingBean
    public ConsoleUserResolver consoleUserResolver(DevConsoleUserProperties properties, Environment environment) {
        if (!properties.enabled()) {
            // T14b 之前生产环境没有控制台身份来源：一律拒绝
            return token -> Optional.empty();
        }
        if (!environment.matchesProfiles(DEV_PROFILES)) {
            throw new IllegalStateException(
                    "开发期控制台令牌（meridian.security.dev-console-users.enabled）只能在 dev 或 test profile 中启用，当前 profile："
                            + Arrays.toString(environment.getActiveProfiles()));
        }
        return new DevConsoleUserResolver(properties);
    }

    /**
     * 管理 API 的认证过滤器。
     *
     * @param resolver 控制台用户令牌的来源
     * @param errors Spring MVC 的异常解析器
     * @return 过滤器
     */
    @Bean
    public ConsoleUserFilter consoleUserFilter(
            ConsoleUserResolver resolver, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        return new ConsoleUserFilter(resolver, errors);
    }
}
