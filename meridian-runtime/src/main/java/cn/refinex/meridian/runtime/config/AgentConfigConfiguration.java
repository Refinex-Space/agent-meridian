package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.engine.spi.engine.AgentEngine;
import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.runtime.approval.app.PolicyStorePort;
import cn.refinex.meridian.runtime.approval.infra.ControlPlanePolicyStore;
import cn.refinex.meridian.runtime.approval.infra.DatabasePolicyStore;
import cn.refinex.meridian.runtime.approval.infra.PermissionRuleRepository;
import cn.refinex.meridian.runtime.modelrouting.app.ModelRoutingProperties;
import cn.refinex.meridian.runtime.session.app.AgentConfigProvider;
import cn.refinex.meridian.runtime.session.infra.ControlPlaneAgentConfigProvider;
import cn.refinex.meridian.security.servicejwt.ServiceClientProperties;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.security.servicejwt.ServiceRestClients;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * 智能体配置的来源（ADR-0047）：按 {@code meridian.runtime.agent-config.source} 装配 {@link AgentConfigProvider} 与租户、项目策略的
 * {@link PolicyStorePort}。{@code control-plane}（默认）经内部接口读取并缓存，订阅 {@code config.invalidate} 立即淘汰；{@code file}
 * 读开发配置目录与 {@code rt.permission_rules}，只允许 dev、test profile（与模型目录的 {@code catalog-source} 相同）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentConfigProperties.class)
public class AgentConfigConfiguration {

    /** 来源的配置键。 */
    static final String SOURCE = "meridian.runtime.agent-config.source";

    /**
     * 智能体配置来源。
     *
     * @param properties 配置
     * @param directory 开发配置目录（{@code file} 时使用）
     * @param modelRouting 模型路由配置，取端点策略校验联网搜索后端
     * @param environment 环境，取激活的 profile
     * @param serviceClient 服务间调用配置，取 control-plane 地址
     * @param clients 服务间调用客户端的构造入口
     * @return 配置来源
     */
    @Bean
    public AgentConfigProvider agentConfigProvider(
            AgentConfigProperties properties,
            @Value("${meridian.runtime.dev-agent-config.directory:dev-config/agents}") Path directory,
            ModelRoutingProperties modelRouting,
            Environment environment,
            ServiceClientProperties serviceClient,
            ServiceRestClients clients) {
        if (AgentConfigProperties.FILE.equals(properties.source())) {
            requireDevOrTest(environment);
            return new FileAgentConfigProvider(directory);
        }
        return new ControlPlaneAgentConfigProvider(
                controlPlane(serviceClient, clients),
                modelRouting.endpointPolicy(),
                properties.cacheTtl(),
                properties.immutableCacheTtl());
    }

    /**
     * 租户与项目策略。
     *
     * @param properties 配置
     * @param rules {@code rt.permission_rules} 的仓储（{@code file} 时使用）
     * @param clock 时钟
     * @param environment 环境，取激活的 profile
     * @param serviceClient 服务间调用配置，取 control-plane 地址
     * @param clients 服务间调用客户端的构造入口
     * @return 策略来源
     */
    @Bean
    public PolicyStorePort policyStorePort(
            AgentConfigProperties properties,
            PermissionRuleRepository rules,
            Clock clock,
            Environment environment,
            ServiceClientProperties serviceClient,
            ServiceRestClients clients) {
        if (AgentConfigProperties.FILE.equals(properties.source())) {
            requireDevOrTest(environment);
            return new DatabasePolicyStore(rules, clock);
        }
        return new ControlPlanePolicyStore(controlPlane(serviceClient, clients), properties.cacheTtl());
    }

    /**
     * 订阅 {@code config.invalidate}，淘汰智能体配置与策略的缓存；租户设置变更时淘汰该租户已装配的实例（D32）。
     *
     * @param redis Redis 消息封装
     * @param provider 配置来源
     * @param policies 策略来源
     * @param engine 内核；Worker 关闭时不存在
     * @return 订阅组件
     */
    @Bean
    @ConditionalOnProperty(name = SOURCE, havingValue = AgentConfigProperties.CONTROL_PLANE, matchIfMissing = true)
    public AgentConfigInvalidationListener agentConfigInvalidationListener(
            ReactiveRedisMessaging redis,
            AgentConfigProvider provider,
            PolicyStorePort policies,
            ObjectProvider<AgentEngine> engine) {
        return new AgentConfigInvalidationListener(redis, provider, policies, engine);
    }

    private static void requireDevOrTest(Environment environment) {
        if (!environment.matchesProfiles("dev | test")) {
            throw new IllegalStateException(SOURCE + "=file 只允许在 dev、test profile 使用；生产经 control-plane 读取");
        }
    }

    private static RestClient controlPlane(ServiceClientProperties serviceClient, ServiceRestClients clients) {
        if (!serviceClient.hasControlPlane()) {
            throw new IllegalStateException(
                    SOURCE + "=control-plane 需要 meridian.security.service-client.control-plane-url");
        }
        return clients.create(serviceClient.controlPlaneUrl(), ServiceJwts.CONTROL_PLANE);
    }
}
