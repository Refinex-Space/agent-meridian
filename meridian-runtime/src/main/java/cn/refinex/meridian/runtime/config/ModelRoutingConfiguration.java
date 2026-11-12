package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.engine.spi.port.SecretResolver;
import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.runtime.modelrouting.app.BudgetLimitPort;
import cn.refinex.meridian.runtime.modelrouting.app.ModelCatalogPort;
import cn.refinex.meridian.runtime.modelrouting.app.ModelRoutingProperties;
import cn.refinex.meridian.runtime.modelrouting.infra.ControlPlaneModelCatalog;
import cn.refinex.meridian.runtime.modelrouting.infra.FileBudgetLimits;
import cn.refinex.meridian.runtime.modelrouting.infra.FileModelCatalog;
import cn.refinex.meridian.runtime.modelrouting.infra.ProviderVectorClient;
import cn.refinex.meridian.security.servicejwt.ServiceClientProperties;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.security.servicejwt.ServiceRestClients;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * 模型路由的装配（架构设计「模型调用」）：供应商与模型目录、凭据解析、预算限额。{@code ModelGateway} 的实现
 * {@code modelrouting.app.RoutingModelGateway} 由组件扫描创建；内核的模型工厂在 {@link ModelFactoryAutoConfiguration}。
 *
 * <p>目录与凭据按 {@code meridian.runtime.model-routing.catalog-source} 选择（ADR-0045）：{@code control-plane}（默认）经内部接口解析，
 * 并订阅 {@code config.invalidate} 淘汰缓存；{@code file} 读开发配置与环境变量，只允许在 {@code dev}、{@code test} profile 使用。预算
 * 限额仍是开发实现，T16 起改为 control-plane 同步到 Redis 的值。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelRoutingProperties.class)
public class ModelRoutingConfiguration {

    /** 目录来源的配置键。 */
    static final String CATALOG_SOURCE = "meridian.runtime.model-routing.catalog-source";

    /**
     * 供应商与模型目录。
     *
     * @param properties 配置
     * @param environment 环境，取激活的 profile
     * @param serviceClient 服务间调用配置，取 control-plane 地址
     * @param clients 服务间调用客户端的构造入口
     * @return 目录
     * @throws IllegalStateException 文件目录用在 dev、test 之外，或没有配置 control-plane 地址时
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelCatalogPort modelCatalogPort(
            ModelRoutingProperties properties,
            Environment environment,
            ServiceClientProperties serviceClient,
            ServiceRestClients clients) {
        if (ModelRoutingProperties.FILE.equals(properties.catalogSource())) {
            requireDevOrTest(environment);
            return new FileModelCatalog(properties.providersDirectory(), Set.of(environment.getActiveProfiles()));
        }
        return new ControlPlaneModelCatalog(
                controlPlane(serviceClient, clients), properties.endpointPolicy(), properties.catalogCacheTtl());
    }

    /**
     * 凭据解析（架构设计「端口」中 {@code SecretResolver} 由 {@code config} 实现）。
     *
     * @param properties 配置
     * @param environment 环境，取激活的 profile
     * @param serviceClient 服务间调用配置，取 control-plane 地址
     * @param clients 服务间调用客户端的构造入口
     * @return 凭据解析
     * @throws IllegalStateException 环境变量实现用在 dev、test 之外，或没有配置 control-plane 地址时
     */
    @Bean
    @ConditionalOnMissingBean
    public SecretResolver secretResolver(
            ModelRoutingProperties properties,
            Environment environment,
            ServiceClientProperties serviceClient,
            ServiceRestClients clients) {
        if (ModelRoutingProperties.FILE.equals(properties.catalogSource())) {
            requireDevOrTest(environment);
            return new EnvironmentSecretResolver();
        }
        return new ControlPlaneSecretResolver(controlPlane(serviceClient, clients), properties.secretCacheTtl());
    }

    /**
     * 订阅 {@code config.invalidate}，淘汰目录与凭据的缓存。
     *
     * @param redis Redis 消息封装
     * @param catalog 模型目录
     * @param secrets 凭据解析
     * @return 订阅组件
     */
    @Bean
    @ConditionalOnProperty(
            name = CATALOG_SOURCE,
            havingValue = ModelRoutingProperties.CONTROL_PLANE,
            matchIfMissing = true)
    public CatalogInvalidationListener catalogInvalidationListener(
            ReactiveRedisMessaging redis, ModelCatalogPort catalog, SecretResolver secrets) {
        return new CatalogInvalidationListener(redis, catalog, secrets);
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
     * Embedding 与 rerank 的供应商 HTTP 客户端，一个连接池，容器关闭时关闭。
     *
     * @param clock 时钟
     * @return 客户端
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ProviderVectorClient providerVectorClient(Clock clock) {
        return new ProviderVectorClient(clock);
    }

    /** 文件目录与环境变量中的凭据不按租户隔离，只能用于开发与测试（ADR-0045）。 */
    private static void requireDevOrTest(Environment environment) {
        if (!environment.matchesProfiles("dev | test")) {
            throw new IllegalStateException(CATALOG_SOURCE + "=file 只允许在 dev、test profile 使用；生产经 control-plane 解析");
        }
    }

    private static RestClient controlPlane(ServiceClientProperties serviceClient, ServiceRestClients clients) {
        if (!serviceClient.hasControlPlane()) {
            throw new IllegalStateException(CATALOG_SOURCE
                    + "=control-plane 需要配置 meridian.security.service-client.control-plane-url（开发与测试可改用 file）");
        }
        return clients.create(serviceClient.controlPlaneUrl(), ServiceJwts.CONTROL_PLANE);
    }
}
