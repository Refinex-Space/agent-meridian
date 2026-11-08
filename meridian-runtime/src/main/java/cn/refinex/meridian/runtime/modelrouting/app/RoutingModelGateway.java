package cn.refinex.meridian.runtime.modelrouting.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.error.BudgetExceededException;
import cn.refinex.meridian.engine.spi.error.RateLimitTimeoutException;
import cn.refinex.meridian.engine.spi.port.ModelGateway;
import cn.refinex.meridian.engine.spi.port.SecretResolver;
import cn.refinex.meridian.engine.spi.port.SecretValue;
import cn.refinex.meridian.runtime.modelrouting.domain.BudgetScope;
import cn.refinex.meridian.runtime.modelrouting.domain.CatalogModel;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelKind;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelRoutingErrorCode;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelUsageMessage;
import cn.refinex.meridian.runtime.modelrouting.domain.ProviderAccount;
import cn.refinex.meridian.runtime.modelrouting.domain.RateLimit;
import cn.refinex.meridian.runtime.modelrouting.infra.RedisBudgetLedger;
import cn.refinex.meridian.runtime.modelrouting.infra.RedisRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * {@link ModelGateway} 的实现，见架构设计「端口」「模型调用」「计量与预算」：
 *
 * <ul>
 *   <li>路由：模型目录（{@link ModelCatalogPort}）加凭据（{@link SecretResolver}，只在本次调用的路由中带出明文）。
 *   <li>限流："供应商账号加模型"的 RPM、TPM 令牌桶（{@link RedisRateLimiter}），需要等待时按令牌桶给出的时间等，累计等待会超过
 *       {@link ModelRoutingProperties#rateLimitMaxWait()}（30 秒）时立即抛 {@link RateLimitTimeoutException}，不空等到超时。
 *   <li>预算：租户、项目、API Key、会话四级（{@link RedisBudgetLedger}），硬阈值来自 {@link BudgetLimitPort}，任一级不足抛
 *       {@link BudgetExceededException}。范围取 Reactor Context 中的 {@code PlatformContext}，缺少的级别（例如直通接口没有会话）不计。
 *   <li>结算：先写用量记录（outbox {@code usage}），再按"输入加输出"修正各级计数、退回 TPM 桶多扣的 token，最后记指标。先写记录再改
 *       计数：Redis 失败时计数偏多（预扣留在计数里），由对账按账本修正，不会少记。
 * </ul>
 *
 * <p>阻塞的读取（目录文件、Redis、数据库）都经 {@link Blocking} 在虚拟线程上执行。线程安全：无状态。
 */
@Service
public class RoutingModelGateway implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(RoutingModelGateway.class);

    private final ModelCatalogPort catalog;
    private final BudgetLimitPort budgets;
    private final SecretResolver secrets;
    private final RedisRateLimiter rateLimiter;
    private final RedisBudgetLedger ledger;
    private final UsageRecorder usage;
    private final ModelMetrics metrics;
    private final ModelRoutingProperties properties;
    private final Clock clock;

    /**
     * 创建网关。
     *
     * @param catalog 供应商与模型目录
     * @param budgets 预算硬阈值
     * @param secrets 凭据解析
     * @param rateLimiter 令牌桶
     * @param ledger 预算计数
     * @param usage 用量记录
     * @param metrics 指标
     * @param properties 配置
     * @param clock 时钟
     */
    public RoutingModelGateway(
            ModelCatalogPort catalog,
            BudgetLimitPort budgets,
            SecretResolver secrets,
            RedisRateLimiter rateLimiter,
            RedisBudgetLedger ledger,
            UsageRecorder usage,
            ModelMetrics metrics,
            ModelRoutingProperties properties,
            Clock clock) {
        this.catalog = catalog;
        this.budgets = budgets;
        this.secrets = secrets;
        this.rateLimiter = rateLimiter;
        this.ledger = ledger;
        this.usage = usage;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<ModelDescriptor> describe(String modelId) {
        return Blocking.call(() -> model(modelId))
                .map(model -> new ModelDescriptor(modelId, model.contextWindow(), model.maxOutputTokens()));
    }

    @Override
    public Mono<ModelRoute> resolve(String modelId) {
        return resolve(modelId, ModelKind.CHAT);
    }

    /**
     * 解析指定种类的模型：对话模型经 {@link #resolve(String)}，Embedding 与 rerank 由 {@link EmbeddingService}、
     * {@link RerankService} 调用。模型自己的端点覆盖账号的端点。
     *
     * @param modelId 模型 ID
     * @param kind 调用方要求的种类
     * @return 路由信息；模型不存在以 {@code NotFoundException} 失败，种类不符以 {@code ValidationException} 失败
     */
    public Mono<ModelRoute> resolve(String modelId, ModelKind kind) {
        return Blocking.call(() -> {
                    CatalogModel model = model(modelId);
                    if (model.kind() != kind) {
                        throw new ValidationException(
                                ModelRoutingErrorCode.UNSUPPORTED_OPERATION,
                                "模型 " + modelId + " 是 " + model.kind().value() + " 模型，不能用于 " + kind.value() + " 调用");
                    }
                    return new Resolved(model, account(model));
                })
                .flatMap(resolved -> {
                    ProviderAccount account = resolved.account();
                    Mono<Optional<SecretValue>> credential = account.secretRef() == null
                            ? Mono.just(Optional.empty())
                            : secrets.resolve(account.secretRef()).map(Optional::of);
                    String baseUrl = resolved.model().baseUrl() != null
                            ? resolved.model().baseUrl()
                            : account.baseUrl();
                    return credential.map(apiKey -> new ModelRoute(
                            modelId,
                            account.providerAccountId(),
                            account.provider(),
                            baseUrl,
                            resolved.model().upstreamModel(),
                            apiKey.orElse(null),
                            resolved.model().contextWindow(),
                            resolved.model().maxOutputTokens()));
                });
    }

    @Override
    public Mono<Void> awaitRateLimit(ModelRoute route, int estimatedTokens) {
        return Blocking.call(() -> rateLimit(route)).flatMap(limit -> {
            if (limit.equals(RateLimit.UNLIMITED)) {
                return Mono.empty();
            }
            return acquire(route, limit, estimatedTokens, Duration.ZERO);
        });
    }

    @Override
    public Mono<Void> precheck(ModelRoute route, int estimatedTokens) {
        Mono<Void> rateLimitCheck = Blocking.call(() -> rateLimit(route)).flatMap(limit -> {
            if (limit.equals(RateLimit.UNLIMITED)) {
                return Mono.empty();
            }
            return Blocking.call(() -> rateLimiter.acquire(
                            RedisRateLimiter.key(route.providerAccountId(), route.upstreamModel()),
                            limit,
                            estimatedTokens,
                            clock.instant(),
                            false))
                    .flatMap(wait -> wait > properties.rateLimitMaxWait().toMillis()
                            ? Mono.error(rateLimitTimeout(route, Duration.ofMillis(wait)))
                            : Mono.empty());
        });
        Mono<Void> budgetCheck = PlatformContexts.currentReactive()
                .flatMap(context -> Blocking.call(() -> ledger.reserve(
                        context.tenantId(),
                        limitedScopes(context),
                        "precheck",
                        estimatedTokens,
                        properties.reservationTtl(),
                        false)))
                .flatMap(insufficient -> insufficient.isPresent()
                        ? Mono.<Void>error(budgetExceeded(insufficient.get()))
                        : Mono.<Void>empty());
        return rateLimitCheck.then(budgetCheck);
    }

    @Override
    public Mono<BudgetReservation> reserve(ModelRoute route, int estimatedTokens) {
        return PlatformContexts.currentReactive().flatMap(context -> {
            String reservationId = Ids.newUlid();
            return Blocking.call(() -> ledger.reserve(
                            context.tenantId(),
                            limitedScopes(context),
                            reservationId,
                            estimatedTokens,
                            properties.reservationTtl(),
                            true))
                    .flatMap(insufficient -> insufficient.isPresent()
                            ? Mono.<BudgetReservation>error(budgetExceeded(insufficient.get()))
                            : Mono.just(new BudgetReservation(reservationId, estimatedTokens)));
        });
    }

    @Override
    public Mono<Void> settle(BudgetReservation reservation, ModelCallRecord record) {
        return PlatformContexts.currentReactive().flatMap(context -> {
            ModelUsageMessage message = ModelUsageMessage.of(context, record, clock.instant());
            long billable = message.billableTokens();
            return Blocking.run(() -> usage.record(message))
                    .then(Blocking.run(() -> {
                        ledger.settle(
                                context.tenantId(), BudgetScope.of(context), reservation.reservationId(), billable);
                        RateLimit limit = rateLimit(record.route());
                        int unused = (int) Math.max(0, reservation.reservedTokens() - billable);
                        rateLimiter.refund(
                                RedisRateLimiter.key(
                                        record.route().providerAccountId(),
                                        record.route().upstreamModel()),
                                limit,
                                unused,
                                clock.instant());
                    }))
                    .doOnSuccess(done -> {
                        metrics.tokens(record.route().modelId(), record.usage());
                        if (record.firstTokenLatency() != null) {
                            metrics.firstToken(record.route().modelId(), record.firstTokenLatency());
                        }
                    });
        });
    }

    @Override
    public Mono<Void> release(BudgetReservation reservation) {
        return PlatformContexts.currentReactive()
                .flatMap(context -> Blocking.run(() ->
                        ledger.settle(context.tenantId(), BudgetScope.of(context), reservation.reservationId(), 0)));
    }

    /** 等待令牌桶：每次按桶给出的时间等，累计等待会超过上限时立即失败。 */
    private Mono<Void> acquire(ModelRoute route, RateLimit limit, int tokens, Duration waited) {
        String key = RedisRateLimiter.key(route.providerAccountId(), route.upstreamModel());
        return Blocking.call(() -> rateLimiter.acquire(key, limit, tokens, clock.instant(), true))
                .flatMap(waitMillis -> {
                    if (waitMillis == 0) {
                        return Mono.empty();
                    }
                    Duration wait = Duration.ofMillis(waitMillis);
                    Duration total = waited.plus(wait);
                    if (total.compareTo(properties.rateLimitMaxWait()) > 0) {
                        return Mono.error(rateLimitTimeout(route, total));
                    }
                    log.debug("模型限流等待 model={} waitMillis={}", route.modelId(), waitMillis);
                    return Mono.delay(wait).then(acquire(route, limit, tokens, total));
                });
    }

    /** 调用上下文涉及的各级预算范围，带上硬阈值。在虚拟线程上执行（读限额会阻塞）。 */
    private List<BudgetScope> limitedScopes(PlatformContext context) {
        return BudgetScope.of(context).stream()
                .map(scope -> scope.withHardLimit(budgets.hardLimit(scope.level(), scope.scopeId())))
                .toList();
    }

    private CatalogModel model(String modelId) {
        return catalog.model(modelId)
                .orElseThrow(() -> new NotFoundException(ModelRoutingErrorCode.MODEL_NOT_FOUND, "模型不存在：" + modelId));
    }

    private ProviderAccount account(CatalogModel model) {
        return catalog.account(model.providerAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "模型目录不一致：模型 " + model.modelId() + " 的供应商账号不存在 " + model.providerAccountId()));
    }

    /** 本次调用的限流参数：模型自己的，没有时取账号的。 */
    private RateLimit rateLimit(ModelRoute route) {
        CatalogModel model = model(route.modelId());
        return model.rateLimit() != null ? model.rateLimit() : account(model).rateLimit();
    }

    private MeridianException budgetExceeded(BudgetScope scope) {
        metrics.budgetRejected(scope.level());
        String level = switch (scope.level()) {
            case TENANT -> "租户";
            case PROJECT -> "项目";
            case API_KEY -> "API Key";
            case SESSION -> "会话";
        };
        return new BudgetExceededException(level + "预算不足，无法发起模型调用");
    }

    private static RateLimitTimeoutException rateLimitTimeout(ModelRoute route, Duration wait) {
        return new RateLimitTimeoutException("模型 " + route.modelId() + " 限流需要等待 " + wait.toMillis() + " 毫秒，超过上限");
    }

    /** 解析出的目录模型与供应商账号。 */
    private record Resolved(CatalogModel model, ProviderAccount account) {}
}
