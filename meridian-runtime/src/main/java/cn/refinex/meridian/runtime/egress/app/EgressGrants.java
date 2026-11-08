package cn.refinex.meridian.runtime.egress.app;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.spi.port.AgentConfigPort;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.runtime.egress.domain.EgressAllowlist;
import cn.refinex.meridian.runtime.egress.domain.EgressGrant;
import cn.refinex.meridian.runtime.egress.infra.system.SandboxAddressRepository;
import cn.refinex.meridian.runtime.egress.infra.system.SandboxAddressRepository.SandboxAddress;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 按源 IP 解析出的出网授权（{@link EgressGrant}）的本节点缓存（架构设计「沙箱出网」：结果缓存 30 秒）。
 *
 * <p>未命中时在缓存的按键加载中查 {@code rt.sessions.sandbox_pod_ip} 与环境配置；找不到会话不缓存，下一次请求重新查询。会话的 Pod IP
 * 变化（沙箱删除、回收、重建、新认领）时由 {@link EgressRevocations} 淘汰，所以"同 IP 的新 Pod"拿不到上一个会话的授权。
 *
 * <p>淘汰与加载的竞争：加载在 Caffeine 的按键计算中执行，淘汰会等正在进行的加载结束后再删除。改动 Pod IP 的事务提交之前开始的加载
 * 读到的是旧数据，但它写入的条目一定会被提交之后的淘汰删掉；提交之后开始的加载读到新数据。
 *
 * <p>命中率经 Micrometer 的 {@code CaffeineCacheMetrics} 导出（{@code cache_gets_total{cache="egress_grants",result="hit|miss"}}）。
 * 线程安全：可共享。
 */
@Component
public class EgressGrants {

    /** 授权的缓存时长，架构设计「沙箱出网」规定 30 秒。 */
    public static final Duration TTL = Duration.ofSeconds(30);

    /** 指标中的缓存名。 */
    public static final String CACHE_NAME = "egress_grants";

    /** 缓存条目的上限：每个持有沙箱的会话一条，远大于单个集群能同时运行的沙箱数，只防止异常的源 IP 撑大内存。 */
    static final long MAX_ENTRIES = 10_000;

    /** 读取环境配置的等待上限：超时按查询失败处理，代理收到错误后拒绝本次请求。 */
    static final Duration CONFIG_TIMEOUT = Duration.ofSeconds(2);

    private static final Logger log = LoggerFactory.getLogger(EgressGrants.class);

    private final SandboxAddressRepository addresses;
    private final AgentConfigPort configs;
    private final Cache<String, EgressGrant> cache;

    /**
     * 创建缓存。
     *
     * @param addresses 按 Pod IP 找会话
     * @param configs 环境配置
     * @param meters 指标注册表
     */
    @Autowired
    public EgressGrants(SandboxAddressRepository addresses, AgentConfigPort configs, MeterRegistry meters) {
        this(addresses, configs, meters, Ticker.systemTicker());
    }

    /**
     * 创建缓存，测试用：可控的计时器。
     *
     * @param addresses 按 Pod IP 找会话
     * @param configs 环境配置
     * @param meters 指标注册表
     * @param ticker 缓存过期用的计时器
     */
    EgressGrants(SandboxAddressRepository addresses, AgentConfigPort configs, MeterRegistry meters, Ticker ticker) {
        this.addresses = addresses;
        this.configs = configs;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(MAX_ENTRIES)
                .ticker(ticker)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meters, cache, CACHE_NAME);
    }

    /**
     * 取源 IP 当前的授权，未命中时查询并缓存。阻塞，在虚拟线程上调用。
     *
     * @param podIp 源 Pod IP
     * @return 授权；IP 不属于任何持有沙箱的会话、或属于多个会话时为空
     */
    public Optional<EgressGrant> find(String podIp) {
        return Optional.ofNullable(cache.get(podIp, this::load));
    }

    /**
     * 淘汰本节点上一个 IP 的授权。正在进行的同一 IP 的加载结束后才删除。
     *
     * @param podIp Pod IP
     */
    public void evict(String podIp) {
        cache.invalidate(podIp);
    }

    private EgressGrant load(String podIp) {
        List<SandboxAddress> found = addresses.findByPodIp(podIp);
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() > 1) {
            log.warn("多个会话记录了同一个沙箱 Pod IP，拒绝出网直到旧记录被更新 podIp={} sessions={}", podIp, found);
            return null;
        }
        SandboxAddress address = found.getFirst();
        PlatformContext context = PlatformContext.builder()
                .tenantId(address.tenantId())
                .projectId(address.projectId())
                .sessionId(address.sessionId())
                .build();
        EnvironmentSpec environment = configs.environment(address.environmentId(), address.environmentRevision())
                .contextWrite(PlatformContexts.write(context))
                .block(CONFIG_TIMEOUT);
        if (environment == null) {
            throw new IllegalStateException("环境配置为空：" + address.environmentId());
        }
        EgressAllowlist allowlist = EgressAllowlist.of(environment.egressAllowlist());
        if (!allowlist.ignored().isEmpty()) {
            log.warn(
                    "环境的出网白名单有不合法的条目，已忽略 environmentId={} revision={} ignored={}",
                    address.environmentId(),
                    address.environmentRevision(),
                    allowlist.ignored());
        }
        return new EgressGrant(
                address.sessionId(),
                address.tenantId(),
                address.projectId(),
                address.environmentId(),
                address.environmentRevision(),
                allowlist);
    }
}
