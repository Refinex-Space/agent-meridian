package cn.refinex.meridian.runtime.modelrouting.infra;

import cn.refinex.meridian.api.internal.model.CatalogProviderAccount;
import cn.refinex.meridian.api.internal.model.CatalogRateLimit;
import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.engine.spi.error.ProviderRejectedException;
import cn.refinex.meridian.engine.spi.error.ProviderUnavailableException;
import cn.refinex.meridian.engine.spi.port.ModelGateway.Provider;
import cn.refinex.meridian.runtime.modelrouting.app.ModelCatalogPort;
import cn.refinex.meridian.runtime.modelrouting.domain.CatalogEntry;
import cn.refinex.meridian.runtime.modelrouting.domain.CatalogModel;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelKind;
import cn.refinex.meridian.runtime.modelrouting.domain.ProviderAccount;
import cn.refinex.meridian.runtime.modelrouting.domain.RateLimit;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 经 control-plane 的 {@code GET /internal/v1/models/{model_id}} 读取的模型目录（ADR-0045 第 3、4 条）：按模型 ID 缓存模型与供应商账号，
 * 写入后 {@code catalog-cache-ttl}（默认 60 秒）过期；收到 {@code config.invalidate} 时立即淘汰（{@link #evictModel}、
 * {@link #evictAccount}、{@link #evictPriceBook}），过期时间只是通知丢失时的兜底。不存在的模型不缓存。
 *
 * <p>每次读取（缓存刷新）都按 {@link EndpointPolicy} 以当前的 DNS 结果再校验一次端点（D52）：不允许时以终止的
 * {@link ProviderRejectedException} 失败，不进缓存。control-plane 不可用时以可重试的 {@link ProviderUnavailableException} 失败。
 *
 * <p>目录不含凭据明文，凭据经 {@code SecretResolver} 另行解析。线程安全：可被并发调用（Caffeine 按键计算，同一模型同时只读取一次）。
 */
public final class ControlPlaneModelCatalog implements ModelCatalogPort {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneModelCatalog.class);

    /** 缓存容量：一个节点同时使用的模型远少于此。 */
    static final int MAX_ENTRIES = 10_000;

    private final RestClient client;
    private final EndpointPolicy endpoints;
    private final Cache<String, CatalogEntry> cache;

    /**
     * 创建目录。
     *
     * @param client 指向 control-plane 的服务间调用客户端（{@code ServiceRestClients.create}）
     * @param endpoints 供应商端点的地址策略
     * @param ttl 缓存时间
     */
    public ControlPlaneModelCatalog(RestClient client, EndpointPolicy endpoints, Duration ttl) {
        this.client = client;
        this.endpoints = endpoints;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(ttl)
                .build();
    }

    @Override
    public Optional<CatalogEntry> find(String modelId) {
        return Optional.ofNullable(cache.get(modelId, this::fetch));
    }

    /**
     * 淘汰一个模型（{@code config.invalidate} 的 {@code model}）。
     *
     * @param modelId 模型 ID
     */
    public void evictModel(String modelId) {
        cache.invalidate(modelId);
    }

    /**
     * 淘汰一个供应商账号下的全部模型（{@code provider_account}）。
     *
     * @param providerAccountId 供应商账号 ID
     */
    public void evictAccount(String providerAccountId) {
        cache.asMap()
                .values()
                .removeIf(entry -> entry.account().providerAccountId().equals(providerAccountId));
    }

    /**
     * 淘汰引用某个价格表的模型（{@code price_book}）：之后结算的用量带上新版本。
     *
     * @param priceBookId 价格表 ID
     */
    public void evictPriceBook(String priceBookId) {
        cache.asMap()
                .values()
                .removeIf(entry -> priceBookId.equals(entry.model().priceBookId()));
    }

    /** 读取一个模型；不存在时返回 null（Caffeine 不缓存）。 */
    private CatalogEntry fetch(String modelId) {
        cn.refinex.meridian.api.internal.model.CatalogModel dto;
        try {
            dto = client.get()
                    .uri("/internal/v1/models/{model_id}", modelId)
                    .retrieve()
                    .body(cn.refinex.meridian.api.internal.model.CatalogModel.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return null;
            }
            throw new ProviderUnavailableException("模型目录暂时无法读取，请稍后重试", e);
        } catch (RestClientException e) {
            throw new ProviderUnavailableException("模型目录暂时无法读取，请稍后重试", e);
        }
        if (dto == null) {
            throw new ProviderUnavailableException("模型目录暂时无法读取，请稍后重试", null);
        }
        CatalogEntry entry = toEntry(dto);
        String baseUrl = entry.baseUrl();
        if (baseUrl != null) {
            endpoints.violation(baseUrl).ifPresent(reason -> {
                log.warn("模型的端点不被允许 modelId={} reason={}", modelId, reason);
                throw new ProviderRejectedException("模型 " + modelId + " 的端点不被允许：" + reason, null);
            });
        }
        return entry;
    }

    private static CatalogEntry toEntry(cn.refinex.meridian.api.internal.model.CatalogModel dto) {
        CatalogProviderAccount account = dto.getProviderAccount();
        RateLimit accountLimit =
                new RateLimit(orZero(account.getRateLimit(), true), orZero(account.getRateLimit(), false));
        CatalogRateLimit modelLimit = dto.getRateLimit();
        // 模型上为空的一项用账号的
        RateLimit effectiveModelLimit =
                modelLimit == null || (modelLimit.getRpm() == null && modelLimit.getTpm() == null)
                        ? null
                        : new RateLimit(
                                modelLimit.getRpm() != null ? modelLimit.getRpm() : accountLimit.rpm(),
                                modelLimit.getTpm() != null ? modelLimit.getTpm() : accountLimit.tpm());
        CatalogModel model = new CatalogModel(
                dto.getModelId(),
                account.getProviderAccountId(),
                dto.getUpstreamModel(),
                dto.getContextWindow(),
                dto.getMaxOutputTokens(),
                effectiveModelLimit,
                ModelKind.of(dto.getKind()),
                dto.getBaseUrl(),
                dto.getMaxBatchSize() == null ? CatalogModel.DEFAULT_MAX_BATCH_SIZE : dto.getMaxBatchSize(),
                dto.getTenantId(),
                dto.getFallbackModelId(),
                dto.getPriceBookId(),
                dto.getPriceBookVersion());
        return new CatalogEntry(
                model,
                new ProviderAccount(
                        account.getProviderAccountId(),
                        Provider.valueOf(account.getProvider().toUpperCase(Locale.ROOT)),
                        account.getBaseUrl(),
                        account.getSecretId(),
                        accountLimit));
    }

    private static int orZero(CatalogRateLimit limit, boolean rpm) {
        if (limit == null) {
            return 0;
        }
        Integer value = rpm ? limit.getRpm() : limit.getTpm();
        return value == null ? 0 : value;
    }
}
