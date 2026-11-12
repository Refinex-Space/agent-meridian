package cn.refinex.meridian.engine.agentscope.model;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.error.ProviderRejectedException;
import cn.refinex.meridian.engine.spi.error.ProviderUnavailableException;
import cn.refinex.meridian.engine.spi.event.TokenUsage;
import cn.refinex.meridian.engine.spi.port.ModelGateway;
import cn.refinex.meridian.engine.spi.port.ModelGateway.BudgetReservation;
import cn.refinex.meridian.engine.spi.port.ModelGateway.CallPurpose;
import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelCallRecord;
import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelRoute;
import cn.refinex.meridian.engine.spi.port.ModelGateway.UsageSource;
import cn.refinex.meridian.engine.spi.port.ProviderRetryPolicy;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 平台的模型：AgentScope {@link ChatModelBase} 的子类，所有模型调用（含压缩器的内部调用）都经过它，见架构设计「模型调用」。
 *
 * <p>每次 {@link #doStream} 从 Reactor Context 取 {@code PlatformContext}，依次：
 * <ol>
 *   <li>经 {@link ModelGateway#resolve} 解析供应商账号与凭据，从 {@link ProviderClients} 取按"供应商账号加模型"缓存的底层客户端；
 *   <li>按"估算输入 token 加 {@code max_tokens}"（{@link TokenEstimator}）等待 RPM 与 TPM 令牌桶，最长 30 秒；
 *   <li>按同一估算预扣四级预算；
 *   <li>调用底层客户端；流结束（完成、出错、取消都算）时按 {@code ChatUsage} 结算并写用量记录，没有收到任何响应时只释放预扣。
 * </ol>
 *
 * <p>错误处理：首包之前的供应商错误（连接失败、5xx、429）重试 2 次（每次重试前重新等待限流，429 遵循 {@code Retry-After}），
 * 仍失败且配置了备用模型时切到备用模型；预算与限流异常不重试、不切换；首包之后的错误不重试、不切换。最终的供应商错误翻译为
 * {@link ProviderUnavailableException}（可重试）或 {@link ProviderRejectedException}（终止）。所以装配时不设置 AgentScope 的
 * {@code fallbackModel}，{@code maxRetries(1)}（ADR-0023），底层客户端每次调用也只尝试一次。
 *
 * <p>上下文窗口按模型目录设置（{@code setContextWindowSize}），{@code CompactionMiddleware} 据此计算压缩阈值。
 *
 * <p>线程安全：无可变状态，同一实例被共享该智能体实例的所有会话并发调用。
 */
public final class RoutedModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(RoutedModel.class);

    /** 底层客户端的执行配置：只尝试一次，重试只由本类针对供应商错误进行。 */
    private static final ExecutionConfig SINGLE_ATTEMPT =
            ExecutionConfig.builder().maxAttempts(1).build();

    private final ModelGateway gateway;
    private final ProviderClients clients;
    private final ProviderRetryPolicy retryPolicy;
    private final ModelSpec spec;
    private final String agentVersionId;

    /**
     * 创建模型。
     *
     * @param gateway 模型调用的平台侧服务
     * @param clients 底层客户端的登记与缓存
     * @param retryPolicy 首包之前的重试策略
     * @param spec 模型引用（主模型、备用模型、生成参数）
     * @param agentVersionId 智能体版本 ID，写进用量记录；可为空
     * @param contextWindow 主模型的上下文窗口，单位 token
     */
    RoutedModel(
            ModelGateway gateway,
            ProviderClients clients,
            ProviderRetryPolicy retryPolicy,
            ModelSpec spec,
            String agentVersionId,
            int contextWindow) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.agentVersionId = agentVersionId;
        setContextWindowSize(contextWindow);
    }

    @Override
    public String getModelName() {
        return spec.modelId();
    }

    /**
     * 压缩前的预检（{@code CompactionGuardMiddleware} 外层调用）：主模型的四级预算是否容得下一次摘要调用、限流能否在上限内拿到配额。
     * 只检查、不占用。
     *
     * @param estimatedInputTokens 摘要调用的输入估算
     * @return 检查通过后完成；否则以 {@code BudgetExceededException} 或 {@code RateLimitTimeoutException} 失败
     */
    public Mono<Void> precheck(int estimatedInputTokens) {
        return gateway.resolve(spec.modelId())
                .flatMap(route -> gateway.precheck(route, estimatedInputTokens + maxTokens(null, route)));
    }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.deferContextual(context -> {
            PlatformContext platform = PlatformContexts.find(context)
                    .orElseThrow(() -> new IllegalStateException("模型调用缺少 PlatformContext，无法解析路由与预算"));
            CompactionWatch watch = context.getOrDefault(CompactionWatch.class, null);
            CallInput input = new CallInput(
                    messages,
                    tools,
                    options,
                    TokenEstimator.messages(messages) + TokenEstimator.tools(tools),
                    watch == null ? CallPurpose.REASONING : CallPurpose.COMPACTION);
            Flux<ChatResponse> call = call(spec.modelId(), false, input)
                    .onErrorResume(ProviderFailure.class, failure -> {
                        if (spec.fallbackModelId() == null || !failure.fallbackAllowed()) {
                            return Flux.error(failure);
                        }
                        log.warn(
                                "主模型在首包之前失败，切换备用模型 model={} fallback={} turnId={}",
                                spec.modelId(),
                                spec.fallbackModelId(),
                                platform.turnId());
                        return call(spec.fallbackModelId(), true, input);
                    })
                    .onErrorMap(ProviderFailure.class, ProviderFailure::toPlatformException);
            return watch == null ? call : call.doOnError(watch::recordFailure);
        });
    }

    /** 用一个模型完成一次调用：解析、限流、预扣、带重试的调用与结算。 */
    private Flux<ChatResponse> call(String modelId, boolean fallback, CallInput input) {
        return gateway.resolve(modelId).flatMapMany(route -> {
            int maxTokens = maxTokens(input.options(), route);
            int estimate = input.inputTokens() + maxTokens;
            ProviderClientFactory factory = clients.factory(route);
            GenerateOptions options = factory.callOptions(route, platformOptions(input.options(), maxTokens));
            Model client = clients.client(route);
            CallMeter meter = new CallMeter(route, input, fallback);
            return gateway.awaitRateLimit(route, estimate)
                    .then(gateway.reserve(route, estimate))
                    .flatMapMany(reservation -> Flux.usingWhen(
                            Mono.just(reservation),
                            held -> attempt(client, input, options, route, estimate, meter, 0),
                            held -> finish(held, meter),
                            (held, error) -> finish(held, meter),
                            held -> finish(held, meter)));
        });
    }

    /** 一次 HTTP 尝试；首包之前的可重试错误按策略等待后重试，其余错误包成 {@link ProviderFailure} 交给上层决定是否降级。 */
    private Flux<ChatResponse> attempt(
            Model client,
            CallInput input,
            GenerateOptions options,
            ModelRoute route,
            int estimate,
            CallMeter meter,
            int retry) {
        ProviderResponseObserver observer = new ProviderResponseObserver();
        return Flux.defer(() -> {
                    meter.startAttempt();
                    return client.stream(input.messages(), input.tools(), options);
                })
                .contextWrite(context -> context.put(ProviderResponseObserver.class, observer))
                .doOnNext(meter::observe)
                .onErrorResume(error -> {
                    ProviderErrors.Kind kind = ProviderErrors.classify(error);
                    if (kind == ProviderErrors.Kind.NOT_PROVIDER) {
                        return Flux.error(error);
                    }
                    boolean beforeFirstPacket = !meter.receivedAny();
                    Integer status = ProviderErrors.statusOf(error);
                    if (beforeFirstPacket && kind == ProviderErrors.Kind.RETRYABLE) {
                        Optional<Duration> wait = retryPolicy.backoff(retry, observer.retryAfter());
                        if (wait.isPresent()) {
                            log.warn(
                                    "模型调用在首包之前失败，{} 毫秒后重试 model={} status={} retry={}",
                                    wait.get().toMillis(),
                                    route.modelId(),
                                    status,
                                    retry + 1);
                            return Mono.delay(wait.get())
                                    .then(gateway.awaitRateLimit(route, estimate))
                                    .thenMany(attempt(client, input, options, route, estimate, meter, retry + 1));
                        }
                    }
                    return Flux.error(new ProviderFailure(error, kind, beforeFirstPacket, status, route.modelId()));
                });
    }

    /** 结算：收到过响应就按用量结算并写用量记录，否则只释放预扣。结算失败只记日志，不影响本次调用的结果。 */
    private Mono<Void> finish(BudgetReservation reservation, CallMeter meter) {
        Mono<Void> settlement = meter.receivedAny()
                ? gateway.settle(reservation, meter.record(agentVersionId))
                : gateway.release(reservation);
        return settlement.onErrorResume(error -> {
            log.error("模型调用的结算失败，预算计数以对账为准 reservationId={}", reservation.reservationId(), error);
            return Mono.empty();
        });
    }

    /** 本次调用的最大输出：调用选项、智能体配置、模型目录默认值，依次取第一个有值的。 */
    private int maxTokens(GenerateOptions options, ModelRoute route) {
        if (options != null && options.getMaxTokens() != null) {
            return options.getMaxTokens();
        }
        return spec.maxOutputTokens() != null ? spec.maxOutputTokens() : route.maxOutputTokens();
    }

    /** 平台决定的选项：流式、最大输出（预扣的上限必须真的发给供应商）、只尝试一次；其余沿用调用方的选项。 */
    private static GenerateOptions platformOptions(GenerateOptions options, int maxTokens) {
        GenerateOptions platform = GenerateOptions.builder().stream(true)
                .maxTokens(maxTokens)
                .executionConfig(SINGLE_ATTEMPT)
                .build();
        return GenerateOptions.mergeOptions(platform, options);
    }

    /** 一次 {@code doStream} 的入参与估算。 */
    private record CallInput(
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options,
            int inputTokens,
            CallPurpose purpose) {}

    /**
     * 一次调用（含重试）的计量：是否收到首包、首个带内容的增量的时间、供应商报告的用量与按增量估算的输出。
     *
     * <p>线程安全：流的信号由 Reactor 串行传递；结算在终止信号之后读取，字段为 volatile。
     */
    private static final class CallMeter {

        private final ModelRoute route;
        private final CallInput input;
        private final boolean fallback;
        private final String requestId = Ids.newUlid();
        private volatile long attemptStartNanos;
        private volatile boolean receivedAny;
        private volatile Duration firstTokenLatency;
        private volatile ChatUsage usage;
        private volatile int estimatedOutput;

        CallMeter(ModelRoute route, CallInput input, boolean fallback) {
            this.route = route;
            this.input = input;
            this.fallback = fallback;
        }

        void startAttempt() {
            attemptStartNanos = System.nanoTime();
        }

        void observe(ChatResponse response) {
            receivedAny = true;
            List<ContentBlock> content = response.getContent();
            if (content != null && !content.isEmpty()) {
                if (firstTokenLatency == null) {
                    firstTokenLatency = Duration.ofNanos(System.nanoTime() - attemptStartNanos);
                }
                int tokens = estimatedOutput;
                for (ContentBlock block : content) {
                    tokens += TokenEstimator.block(block);
                }
                estimatedOutput = tokens;
            }
            if (response.getUsage() != null) {
                usage = response.getUsage();
            }
        }

        boolean receivedAny() {
            return receivedAny;
        }

        ModelCallRecord record(String agentVersionId) {
            ChatUsage reported = usage;
            TokenUsage tokens;
            UsageSource source;
            if (reported != null) {
                tokens = new TokenUsage(
                        Math.max(0, reported.getInputTokens()),
                        Math.max(0, reported.getOutputTokens()),
                        Math.max(0, reported.getCachedTokens()),
                        Math.max(0, reported.getCacheCreationTokens()),
                        Math.max(0, reported.getReasoningTokens()));
                source = UsageSource.PROVIDER;
            } else {
                tokens = TokenUsage.of(input.inputTokens(), estimatedOutput);
                source = UsageSource.ESTIMATED;
            }
            return new ModelCallRecord(
                    requestId, route, agentVersionId, input.purpose(), tokens, source, firstTokenLatency, fallback);
        }
    }

    /**
     * 重试之后仍然失败的供应商错误，降级决策之后翻译为平台异常。
     *
     * <p>只在本类内部传递，不会离开 {@link #doStream}。
     */
    private static final class ProviderFailure extends RuntimeException {

        private final ProviderErrors.Kind kind;
        private final boolean beforeFirstPacket;
        private final Integer status;
        private final String modelId;

        ProviderFailure(
                Throwable cause, ProviderErrors.Kind kind, boolean beforeFirstPacket, Integer status, String modelId) {
            super(cause.getMessage(), cause, false, false);
            this.kind = kind;
            this.beforeFirstPacket = beforeFirstPacket;
            this.status = status;
            this.modelId = modelId;
        }

        /** 只有首包之前的可重试错误才切换备用模型。 */
        boolean fallbackAllowed() {
            return beforeFirstPacket && kind == ProviderErrors.Kind.RETRYABLE;
        }

        RuntimeException toPlatformException() {
            String statusText = status == null ? "" : "（HTTP " + status + "）";
            String phase = beforeFirstPacket ? "" : "，响应中途断开";
            if (kind == ProviderErrors.Kind.RETRYABLE) {
                return new ProviderUnavailableException(
                        "模型 " + modelId + " 的供应商暂时不可用" + statusText + phase, getCause());
            }
            return new ProviderRejectedException("模型 " + modelId + " 的供应商拒绝了请求" + statusText, getCause());
        }
    }
}
