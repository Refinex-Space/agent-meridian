package cn.refinex.meridian.runtime.modelrouting.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.error.ProviderRejectedException;
import cn.refinex.meridian.engine.spi.error.ProviderUnavailableException;
import cn.refinex.meridian.engine.spi.event.TokenUsage;
import cn.refinex.meridian.engine.spi.port.ModelGateway.BudgetReservation;
import cn.refinex.meridian.engine.spi.port.ModelGateway.CallPurpose;
import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelCallRecord;
import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelRoute;
import cn.refinex.meridian.engine.spi.port.ModelGateway.UsageSource;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelKind;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelRoutingErrorCode;
import cn.refinex.meridian.runtime.modelrouting.domain.TextTokens;
import cn.refinex.meridian.runtime.modelrouting.infra.ProviderVectorClient;
import cn.refinex.meridian.runtime.modelrouting.infra.ProviderVectorClient.EmbeddingResult;
import cn.refinex.meridian.runtime.modelrouting.infra.ProviderVectorClient.ProviderCallException;
import cn.refinex.meridian.runtime.modelrouting.infra.ProviderVectorClient.RerankResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Embedding 与 rerank 的用例：直通接口 {@code /v1/embeddings} 与内部接口 {@code /internal/v1/embeddings}、{@code /internal/v1/rerank}
 * 共用（架构设计「模型调用」「计量与预算」「知识库架构」）。
 *
 * <p>每次供应商请求与对话模型走同一条受控通道：{@link RoutingModelGateway} 解析路由（种类必须相符）、等待"供应商账号加模型"的令牌桶、
 * 按输入估算预扣预算、结束时按供应商报告的输入 token 结算并写用量记录（用途 {@code embedding}、{@code rerank}）；失败时只释放预扣。
 * 连接失败、429 与 5xx 重试 2 次（指数退避从 500 毫秒起，遵循不超过 10 秒的 {@code Retry-After}，每次重试前重新等待限流），与
 * {@code RoutedModel} 首包之前的重试规则相同；其余错误不重试。
 *
 * <p>Embedding 的输入超过模型的单次上限（目录的 {@code max_batch_size}）时按顺序拆批，每批是一次独立计量的供应商请求。
 *
 * <p>租户、项目与 API Key 取 Reactor Context 中的 {@code PlatformContext}。线程安全：无状态。
 */
@Service
public class VectorModelService {

    private static final Logger log = LoggerFactory.getLogger(VectorModelService.class);

    /** 最多重试次数（架构设计「模型调用」）。 */
    static final int MAX_RETRIES = 2;

    /** 第一次重试前的等待，之后每次翻倍。 */
    static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);

    /** 原地等待的上限：{@code Retry-After} 要求等更久时不再重试。 */
    static final Duration MAX_IN_PLACE_WAIT = Duration.ofSeconds(10);

    private final RoutingModelGateway gateway;
    private final ModelCatalogPort catalog;
    private final ProviderVectorClient client;

    /**
     * 创建用例。
     *
     * @param gateway 模型路由、限流、预算与计量
     * @param catalog 模型目录，读取批量上限
     * @param client 供应商 HTTP 客户端
     */
    public VectorModelService(RoutingModelGateway gateway, ModelCatalogPort catalog, ProviderVectorClient client) {
        this.gateway = gateway;
        this.catalog = catalog;
        this.client = client;
    }

    /**
     * 计算向量。
     *
     * @param modelId Embedding 模型 ID
     * @param inputs 文本，至少一条
     * @param dimensions 输出维度；可为空
     * @return 与输入一一对应的向量与输入 token 之和；预算、限流、供应商错误以平台异常失败
     */
    public Mono<Embeddings> embed(String modelId, List<String> inputs, Integer dimensions) {
        return gateway.resolve(modelId, ModelKind.EMBEDDING)
                .zipWith(Blocking.call(() -> catalog.model(modelId)
                        .orElseThrow(
                                () -> new NotFoundException(ModelRoutingErrorCode.MODEL_NOT_FOUND, "模型不存在：" + modelId))
                        .maxBatchSize()))
                .flatMap(resolved -> {
                    ModelRoute route = resolved.getT1();
                    List<List<String>> batches = partition(inputs, resolved.getT2());
                    return Flux.fromIterable(batches)
                            .concatMap(batch -> metered(
                                    route,
                                    CallPurpose.EMBEDDING,
                                    TextTokens.estimate(batch),
                                    () -> client.embed(route, batch, dimensions),
                                    EmbeddingResult::promptTokens))
                            .collectList()
                            .map(results -> {
                                List<float[]> vectors = new ArrayList<>(inputs.size());
                                int tokens = 0;
                                for (Metered<EmbeddingResult> result : results) {
                                    vectors.addAll(result.value().vectors());
                                    tokens += result.inputTokens();
                                }
                                return new Embeddings(vectors, tokens);
                            });
                });
    }

    /**
     * 重排。
     *
     * @param modelId rerank 模型 ID
     * @param query 查询
     * @param documents 候选文本
     * @param topN 只返回前 n 条；可为空
     * @return 按得分从高到低的结果与输入 token；预算、限流、供应商错误以平台异常失败
     */
    public Mono<Reranked> rerank(String modelId, String query, List<String> documents, Integer topN) {
        return gateway.resolve(modelId, ModelKind.RERANK)
                .flatMap(route -> metered(
                        route,
                        CallPurpose.RERANK,
                        TextTokens.estimate(query) * documents.size() + TextTokens.estimate(documents),
                        () -> client.rerank(route, query, documents, topN),
                        RerankResult::promptTokens))
                .map(result -> new Reranked(result.value().scores(), result.inputTokens()));
    }

    /** 一次受控的供应商请求：限流、预扣、带重试的调用、结算；失败或取消时释放预扣。 */
    private <T> Mono<Metered<T>> metered(
            ModelRoute route,
            CallPurpose purpose,
            int estimate,
            java.util.function.Supplier<Mono<T>> call,
            Function<T, Integer> reportedTokens) {
        return gateway.awaitRateLimit(route, estimate)
                .then(gateway.reserve(route, estimate))
                .flatMap(reservation -> {
                    long start = System.nanoTime();
                    return attempt(route, estimate, call, 0)
                            .flatMap(value -> {
                                Integer reported = reportedTokens.apply(value);
                                int tokens = reported == null ? estimate : reported;
                                ModelCallRecord record = new ModelCallRecord(
                                        Ids.newUlid(),
                                        route,
                                        null,
                                        purpose,
                                        TokenUsage.of(tokens, 0),
                                        reported == null ? UsageSource.ESTIMATED : UsageSource.PROVIDER,
                                        Duration.ofNanos(System.nanoTime() - start),
                                        false);
                                return gateway.settle(reservation, record)
                                        .onErrorResume(error -> {
                                            log.error(
                                                    "结算失败，预算计数以对账为准 reservationId={}",
                                                    reservation.reservationId(),
                                                    error);
                                            return Mono.empty();
                                        })
                                        .thenReturn(new Metered<>(value, tokens));
                            })
                            .onErrorResume(error -> release(reservation).then(Mono.error(error)))
                            .doOnCancel(() -> release(reservation).subscribe());
                });
    }

    private <T> Mono<T> attempt(ModelRoute route, int estimate, java.util.function.Supplier<Mono<T>> call, int retry) {
        return Mono.defer(call).onErrorResume(ProviderCallException.class, failure -> {
            if (failure.retryable() && retry < MAX_RETRIES) {
                Optional<Duration> wait = backoff(retry, failure.retryAfter());
                if (wait.isPresent()) {
                    log.warn(
                            "供应商请求失败，{} 毫秒后重试 model={} status={} retry={}",
                            wait.get().toMillis(),
                            route.modelId(),
                            failure.status(),
                            retry + 1);
                    return Mono.delay(wait.get())
                            .then(gateway.awaitRateLimit(route, estimate))
                            .then(attempt(route, estimate, call, retry + 1));
                }
            }
            return Mono.error(toPlatformException(route, failure));
        });
    }

    private Mono<Void> release(BudgetReservation reservation) {
        return gateway.release(reservation).onErrorResume(error -> {
            log.error("释放预扣失败，预算计数以对账为准 reservationId={}", reservation.reservationId(), error);
            return Mono.empty();
        });
    }

    /** 第 {@code retry} 次重试前的等待：指数退避与 {@code Retry-After} 取较大者，超过原地等待上限时为空。 */
    static Optional<Duration> backoff(int retry, Optional<Duration> retryAfter) {
        Duration exponential = INITIAL_BACKOFF.multipliedBy(1L << retry);
        Duration wait =
                retryAfter.filter(after -> after.compareTo(exponential) > 0).orElse(exponential);
        return wait.compareTo(MAX_IN_PLACE_WAIT) > 0 ? Optional.empty() : Optional.of(wait);
    }

    private static RuntimeException toPlatformException(ModelRoute route, ProviderCallException failure) {
        String status = failure.status() == null || failure.status() == 0 ? "" : "（HTTP " + failure.status() + "）";
        if (failure.retryable()) {
            return new ProviderUnavailableException("模型 " + route.modelId() + " 的供应商暂时不可用" + status, failure);
        }
        return new ProviderRejectedException("模型 " + route.modelId() + " 的供应商拒绝了请求" + status, failure);
    }

    private static List<List<String>> partition(List<String> inputs, int size) {
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < inputs.size(); from += size) {
            batches.add(inputs.subList(from, Math.min(inputs.size(), from + size)));
        }
        return batches;
    }

    /**
     * 一次计量过的供应商请求的结果。
     *
     * @param value 结果
     * @param inputTokens 计入用量的输入 token
     * @param <T> 结果类型
     */
    private record Metered<T>(T value, int inputTokens) {}

    /**
     * 向量结果。
     *
     * @param vectors 向量，与输入一一对应
     * @param inputTokens 输入 token 之和（供应商报告的，没有报告时为估算）
     */
    public record Embeddings(List<float[]> vectors, int inputTokens) {}

    /**
     * 重排结果。
     *
     * @param scores 得分，从高到低
     * @param inputTokens 输入 token（供应商报告的，没有报告时为估算）
     */
    public record Reranked(List<ProviderVectorClient.RerankScore> scores, int inputTokens) {}
}
