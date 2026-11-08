package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.engine.spi.port.SecretResolver;
import cn.refinex.meridian.runtime.modelrouting.app.BudgetLimitPort;
import cn.refinex.meridian.runtime.modelrouting.app.ModelCatalogPort;
import cn.refinex.meridian.runtime.modelrouting.app.ModelRoutingProperties;
import cn.refinex.meridian.runtime.modelrouting.infra.FileBudgetLimits;
import cn.refinex.meridian.runtime.modelrouting.infra.FileModelCatalog;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 模型路由的装配（架构设计「模型调用」）：开发期的供应商目录、预算限额与凭据解析。{@code ModelGateway} 的实现
 * {@code modelrouting.app.RoutingModelGateway} 由组件扫描创建；内核的模型工厂在 {@link ModelFactoryAutoConfiguration}。
 *
 * <p>三个来源都是开发实现：T15a 起目录与凭据改由 control-plane 下发，T16 起预算限额改为 control-plane 同步到 Redis 的值。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelRoutingProperties.class)
public class ModelRoutingConfiguration {

    /**
     * 供应商与模型目录：读 {@code meridian.runtime.model-routing.providers-directory}（默认 {@code dev-config/providers}），
     * 带 {@code profiles} 的文档按当前 profile 加载（脚本化模型服务只在 {@code dev}、{@code test}）。
     *
     * @param properties 配置
     * @param environment 环境，取激活的 profile
     * @return 目录
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelCatalogPort modelCatalogPort(ModelRoutingProperties properties, Environment environment) {
        return new FileModelCatalog(properties.providersDirectory(), Set.of(environment.getActiveProfiles()));
    }

    /**
     * 预算硬阈值：读 {@code meridian.runtime.model-routing.budgets-directory}（默认 {@code dev-config/budgets}），没有配置时不限。
     *
     * @param properties 配置
     * @return 限额来源
     */
    @Bean
    @ConditionalOnMissingBean
    public BudgetLimitPort budgetLimitPort(ModelRoutingProperties properties) {
        return new FileBudgetLimits(properties.budgetsDirectory());
    }

    /**
     * 凭据解析：开发实现从环境变量读取（架构设计「端口」中 {@code SecretResolver} 由 {@code config} 实现）。
     *
     * @return 凭据解析
     */
    @Bean
    @ConditionalOnMissingBean
    public SecretResolver secretResolver() {
        return new EnvironmentSecretResolver();
    }
}
