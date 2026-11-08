package cn.refinex.meridian.runtime.tooling.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.spi.port.SecretResolver;
import cn.refinex.meridian.engine.spi.port.SecretValue;
import cn.refinex.meridian.engine.spi.port.WebSearchPort;
import cn.refinex.meridian.runtime.session.app.AgentConfigProvider;
import cn.refinex.meridian.runtime.tooling.domain.WebSearchSettings;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * {@link WebSearchPort} 的实现：按调用方租户的设置选择后端（架构设计「平台工具」：后端按租户配置，ADR-0035）。
 *
 * <p>租户从 Reactor Context 的 {@code PlatformContext} 取，设置经 {@link AgentConfigProvider#tenantSettings} 读取（T15b 起由
 * control-plane 下发），API 密钥经 {@link SecretResolver} 在每次调用时解析、只用于这一次请求。租户没有配置或配置的后端没有适配器时，
 * 以"未配置"的错误结束，{@code web_search} 把它作为错误结果交给模型。
 *
 * <p>线程安全：不可变。
 */
public final class TenantWebSearch implements WebSearchPort {

    private static final Logger log = LoggerFactory.getLogger(TenantWebSearch.class);

    private final AgentConfigProvider configs;
    private final SecretResolver secrets;
    private final Map<WebSearchSettings.Backend, WebSearchAdapter> adapters;

    /**
     * 创建实现。
     *
     * @param configs 租户设置来源
     * @param secrets 凭据解析
     * @param adapters 各后端的适配器
     */
    public TenantWebSearch(AgentConfigProvider configs, SecretResolver secrets, List<WebSearchAdapter> adapters) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        Map<WebSearchSettings.Backend, WebSearchAdapter> byBackend = new EnumMap<>(WebSearchSettings.Backend.class);
        adapters.forEach(adapter -> byBackend.put(adapter.backend(), adapter));
        this.adapters = Map.copyOf(byBackend);
    }

    @Override
    public Mono<List<Result>> search(String query, int maxResults) {
        Objects.requireNonNull(query, "query");
        if (maxResults < 1 || maxResults > 20) {
            return Mono.error(new IllegalArgumentException("max_results must be between 1 and 20"));
        }
        return PlatformContexts.currentReactive()
                .flatMap(platform -> Blocking.call(() -> configs.tenantSettings(platform.tenantId())))
                .flatMap(tenant -> tenant.webSearchSettings()
                        .map(Mono::just)
                        .orElseGet(() -> Mono.error(
                                new IllegalStateException("web search is not configured for this organization"))))
                .flatMap(settings -> {
                    WebSearchAdapter adapter = adapters.get(settings.backend());
                    if (adapter == null) {
                        return Mono.error(new IllegalStateException(
                                "web search backend " + settings.backend().value() + " is not available"));
                    }
                    return apiKey(settings)
                            .flatMap(key -> adapter.search(settings, key.orElse(null), query, maxResults));
                });
    }

    private Mono<Optional<SecretValue>> apiKey(WebSearchSettings settings) {
        if (settings.secretId() == null || settings.secretId().isBlank()) {
            return Mono.just(Optional.empty());
        }
        return secrets.resolve(settings.secretId()).map(Optional::of).onErrorMap(error -> {
            log.warn("联网搜索后端的凭据不可用 backend={}", settings.backend(), error);
            return new IllegalStateException("web search credentials are not available");
        });
    }
}
