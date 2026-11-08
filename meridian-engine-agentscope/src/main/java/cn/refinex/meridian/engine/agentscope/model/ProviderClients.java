package cn.refinex.meridian.engine.agentscope.model;

import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelRoute;
import cn.refinex.meridian.engine.spi.port.ModelGateway.Provider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.agentscope.core.model.Model;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 底层客户端的登记与缓存：按供应商类型选工厂，按"供应商账号加模型"缓存构造好的客户端（架构设计「模型调用」第 1 步）。
 *
 * <p>客户端不含凭据，缓存它不会把凭据留在长生命周期对象里；端点变更时以新的端点重新构造（端点也是键的一部分）。
 *
 * <p>线程安全：可被并发调用。
 */
public final class ProviderClients {

    /** 缓存容量：一个节点同时使用的"账号加模型"组合远少于此。 */
    static final int MAX_CLIENTS = 1_000;

    /** 空闲淘汰时间。 */
    static final Duration IDLE_EVICTION = Duration.ofHours(1);

    private final Map<Provider, ProviderClientFactory> factories = new EnumMap<>(Provider.class);
    private final Cache<ClientKey, Model> clients = Caffeine.newBuilder()
            .maximumSize(MAX_CLIENTS)
            .expireAfterAccess(IDLE_EVICTION)
            .build();

    /**
     * 创建登记表。
     *
     * @param factories 各供应商类型的工厂，同一类型只能有一个
     * @throws IllegalArgumentException 同一类型登记了多个工厂时
     */
    public ProviderClients(List<ProviderClientFactory> factories) {
        for (ProviderClientFactory factory : factories) {
            if (this.factories.putIfAbsent(factory.provider(), factory) != null) {
                throw new IllegalArgumentException(
                        "供应商类型重复登记：" + factory.provider().value());
            }
        }
    }

    /**
     * 取路由对应的工厂。
     *
     * @param route 路由信息
     * @return 工厂
     * @throws UnsupportedOperationException 没有该供应商类型的实现时
     */
    ProviderClientFactory factory(ModelRoute route) {
        ProviderClientFactory factory = factories.get(route.provider());
        if (factory == null) {
            throw new UnsupportedOperationException(
                    "尚未支持的供应商类型：" + route.provider().value());
        }
        return factory;
    }

    /**
     * 取路由对应的底层客户端，没有缓存时构造。
     *
     * @param route 路由信息
     * @return 底层客户端
     */
    Model client(ModelRoute route) {
        ProviderClientFactory factory = factory(route);
        return clients.get(
                new ClientKey(route.providerAccountId(), route.upstreamModel(), route.baseUrl()),
                key -> factory.create(route));
    }

    /** 缓存键：供应商账号、供应商侧模型名与端点。 */
    private record ClientKey(String providerAccountId, String upstreamModel, String baseUrl) {}
}
