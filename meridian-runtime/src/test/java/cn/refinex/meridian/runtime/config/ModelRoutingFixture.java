package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.agentscope.model.OpenAiCompatibleClientFactory;
import cn.refinex.meridian.engine.agentscope.model.ProviderClientFactory;
import cn.refinex.meridian.engine.agentscope.model.ProviderClients;
import cn.refinex.meridian.engine.agentscope.model.ProviderHttpTransport;
import cn.refinex.meridian.engine.agentscope.model.RoutedModel;
import cn.refinex.meridian.engine.agentscope.model.RoutedModelFactory;
import cn.refinex.meridian.engine.spi.port.ProviderRetryPolicy;
import cn.refinex.meridian.engine.spi.port.SecretResolver;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.modelrouting.app.ModelMetrics;
import cn.refinex.meridian.runtime.modelrouting.app.ModelRoutingProperties;
import cn.refinex.meridian.runtime.modelrouting.app.RoutingModelGateway;
import cn.refinex.meridian.runtime.modelrouting.app.UsageRecorder;
import cn.refinex.meridian.runtime.modelrouting.app.VectorModelService;
import cn.refinex.meridian.runtime.modelrouting.infra.FileBudgetLimits;
import cn.refinex.meridian.runtime.modelrouting.infra.FileModelCatalog;
import cn.refinex.meridian.runtime.modelrouting.infra.ProviderVectorClient;
import cn.refinex.meridian.runtime.modelrouting.infra.RedisBudgetLedger;
import cn.refinex.meridian.runtime.modelrouting.infra.RedisBudgetLimits;
import cn.refinex.meridian.runtime.modelrouting.infra.RedisRateLimiter;
import cn.refinex.meridian.runtime.modelrouting.infra.system.TurnUsageRepository;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 模型层的集成测试夹具：真实的 {@link RoutingModelGateway}（临时目录中的供应商目录与预算限额、Testcontainers 的 Redis 与
 * PostgreSQL outbox、{@link SimpleMeterRegistry}），加上用平台 HTTP 传输的 {@code RoutedModel}。每个夹具用自己的租户与供应商
 * 账号，令牌桶与预算计数互不干扰。
 *
 * <p>供应商目录写一个 OpenAI 兼容的账号（凭据引用 {@value #SECRET_REF}，值来自测试的环境映射），模型由 {@link #model} 登记。
 */
final class ModelRoutingFixture implements AutoCloseable {

    /** 测试账号的凭据引用。 */
    static final String SECRET_REF = "FIXTURE_API_KEY";

    /** 测试账号的凭据值，供断言请求头。 */
    static final String SECRET_VALUE = "sk-fixture-" + Ids.newUlid();

    final String tenantId = "ten_" + Ids.newUlid();
    final String projectId = "prj_" + Ids.newUlid();
    final String accountId = "prv_" + Ids.newUlid();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final RedisBudgetLedger ledger = new RedisBudgetLedger(RuntimeTestRedis.template(), Clock.systemUTC());
    final RoutingModelGateway gateway;
    final ProviderHttpTransport transport = new ProviderHttpTransport(Clock.systemUTC());
    final ProviderVectorClient vectorClient = new ProviderVectorClient(Clock.systemUTC());
    final FileModelCatalog catalog;

    private final String secretRef;
    private final Path providers;
    private final Path budgets;
    private final String baseUrl;
    private final StringBuilder models = new StringBuilder();
    private String accountRateLimit = "";
    private volatile boolean redisLimits;

    ModelRoutingFixture(Path directory, String baseUrl) {
        this(
                directory,
                baseUrl,
                SECRET_REF,
                new EnvironmentSecretResolver(name -> SECRET_REF.equals(name) ? SECRET_VALUE : null));
    }

    private ModelRoutingFixture(Path directory, String baseUrl, String secretRef, SecretResolver secrets) {
        this.secretRef = secretRef;
        this.providers = directory.resolve("providers");
        this.budgets = directory.resolve("budgets");
        this.baseUrl = baseUrl;
        try {
            Files.createDirectories(providers);
            Files.createDirectories(budgets);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.catalog = new FileModelCatalog(providers);
        this.gateway = new RoutingModelGateway(
                catalog,
                (tenant, scopes) -> redisLimits
                        ? new RedisBudgetLimits(RuntimeTestRedis.template()).withLimits(tenant, scopes)
                        : new FileBudgetLimits(budgets).withLimits(tenant, scopes),
                secrets,
                new RedisRateLimiter(RuntimeTestRedis.template()),
                ledger,
                new UsageRecorder(
                        new OutboxWriter(RuntimeTestDatabase.dataSource(), "rt", Clock.systemUTC()),
                        new TurnUsageRepository(RuntimeTestDatabase.dataSource()),
                        RuntimeTestDatabase.transactions(),
                        Clock.systemUTC()),
                new ModelMetrics(registry),
                ModelRoutingProperties.defaults(),
                Clock.systemUTC());
        writeProviders();
    }

    /**
     * 连真实 DashScope 兼容端点的夹具：凭据引用 {@code DASHSCOPE_API_KEY}，从进程环境变量读取（测试规范「真实模型测试」）。
     *
     * @param directory 临时目录
     * @return 夹具
     */
    static ModelRoutingFixture live(Path directory) {
        return new ModelRoutingFixture(
                directory,
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "DASHSCOPE_API_KEY",
                new EnvironmentSecretResolver());
    }

    /**
     * 登记一个模型。
     *
     * @param modelId 模型 ID
     * @param upstream 供应商侧模型名
     * @param contextWindow 上下文窗口
     * @param maxOutputTokens 默认最大输出
     * @return 本夹具
     */
    ModelRoutingFixture model(String modelId, String upstream, int contextWindow, int maxOutputTokens) {
        models.append("  - model_id: ")
                .append(modelId)
                .append('\n')
                .append("    upstream_model: ")
                .append(upstream)
                .append('\n')
                .append("    context_window: ")
                .append(contextWindow)
                .append('\n')
                .append("    max_output_tokens: ")
                .append(maxOutputTokens)
                .append('\n');
        writeProviders();
        return this;
    }

    /**
     * 登记一个 Embedding 或 rerank 模型。
     *
     * @param modelId 模型 ID
     * @param kind 种类取值：{@code embedding} 或 {@code rerank}
     * @param upstream 供应商侧模型名
     * @param baseUrl 模型自己的端点；可为空，表示用账号的端点
     * @param maxBatchSize 单次请求的输入条数上限
     * @return 本夹具
     */
    ModelRoutingFixture vectorModel(String modelId, String kind, String upstream, String baseUrl, int maxBatchSize) {
        models.append("  - model_id: ")
                .append(modelId)
                .append('\n')
                .append("    kind: ")
                .append(kind)
                .append('\n')
                .append("    upstream_model: ")
                .append(upstream)
                .append('\n')
                .append("    context_window: 8192\n    max_output_tokens: 1\n")
                .append("    max_batch_size: ")
                .append(maxBatchSize)
                .append('\n');
        if (baseUrl != null) {
            models.append("    base_url: ").append(baseUrl).append('\n');
        }
        writeProviders();
        return this;
    }

    /**
     * Embedding 与 rerank 的用例，用本夹具的网关、目录与平台 HTTP 客户端。
     *
     * @return 用例
     */
    VectorModelService vectors() {
        return new VectorModelService(gateway, catalog, vectorClient);
    }

    /**
     * 设置账号的限流参数。
     *
     * @param rpm 每分钟请求数，0 表示不限
     * @param tpm 每分钟 token 数，0 表示不限
     * @return 本夹具
     */
    ModelRoutingFixture rateLimit(int rpm, int tpm) {
        accountRateLimit = "rate_limit:\n  rpm: " + rpm + "\n  tpm: " + tpm + "\n";
        writeProviders();
        return this;
    }

    /**
     * 改为生产的限额来源：读 control-plane 同步到 Redis 的限额哈希，没有设置的级别为 0（ADR-0048）。
     *
     * @return 本夹具
     */
    ModelRoutingFixture limitsFromRedis() {
        redisLimits = true;
        return this;
    }

    /**
     * 设置一级预算的硬阈值。
     *
     * @param level 级别取值，例如 {@code project}
     * @param scopeId 范围 ID
     * @param hardLimit 硬阈值（token）
     * @return 本夹具
     */
    ModelRoutingFixture budget(String level, String scopeId, long hardLimit) {
        write(budgets.resolve(level + "-" + Ids.newUlid() + ".yaml"), """
                kind: budget
                level: %s
                scope_id: "%s"
                hard_limit_tokens: %d
                """.formatted(level, scopeId, hardLimit));
        return this;
    }

    /**
     * 本夹具租户下一个会话的平台上下文。
     *
     * @param sessionId 会话 ID
     * @return 上下文
     */
    PlatformContext context(String sessionId) {
        return PlatformContext.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .apiKeyId("key_fixture")
                .sessionId(sessionId)
                .threadId("thr_" + Ids.newUlid())
                .turnId("trn_" + Ids.newUlid())
                .leaseEpoch(1L)
                .build();
    }

    /**
     * 用平台 HTTP 传输的 {@code RoutedModel}。
     *
     * @param spec 模型引用
     * @param retryPolicy 重试策略
     * @return 模型
     */
    RoutedModel routedModel(ModelSpec spec, ProviderRetryPolicy retryPolicy) {
        return routedModel(spec, retryPolicy, new OpenAiCompatibleClientFactory(transport));
    }

    /**
     * 用指定底层客户端工厂的 {@code RoutedModel}（例如脚本化的供应商）。
     *
     * @param spec 模型引用
     * @param retryPolicy 重试策略
     * @param providerFactory 底层客户端工厂
     * @return 模型
     */
    RoutedModel routedModel(ModelSpec spec, ProviderRetryPolicy retryPolicy, ProviderClientFactory providerFactory) {
        return (RoutedModel) new RoutedModelFactory(gateway, new ProviderClients(List.of(providerFactory)), retryPolicy)
                .create("agv_fixture", spec);
    }

    /**
     * 本租户写入 outbox {@code usage} 主题的用量记录，按写入顺序。
     *
     * @return 每条记录的载荷
     */
    List<Map<String, Object>> usage() {
        return usage(tenantId);
    }

    /**
     * 某个租户写入 outbox {@code usage} 主题的用量记录（Worker 测试的租户来自测试会话），按写入顺序。
     *
     * @param tenantId 租户 ID
     * @return 每条记录的载荷
     */
    static List<Map<String, Object>> usage(String tenantId) {
        return DbAssertions.list(
                        "SELECT payload::text FROM rt.outbox WHERE topic = 'usage' AND payload->>'tenant_id' = ?"
                                + " ORDER BY created_at, id",
                        String.class,
                        tenantId)
                .stream()
                .map(json -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = JsonMappers.lenient().readValue(json, Map.class);
                    return payload;
                })
                .toList();
    }

    /** 快速重试的策略：首次等待 10 毫秒，原地等待最长 5 秒。 */
    static ProviderRetryPolicy fastRetry() {
        return new ProviderRetryPolicy(2, Duration.ofMillis(10), Duration.ofSeconds(5));
    }

    @Override
    public void close() {
        transport.close();
        vectorClient.close();
    }

    private void writeProviders() {
        write(providers.resolve("fixture.yaml"), """
                kind: provider_account
                provider_account_id: %s
                provider: openai_compatible
                base_url: %s
                secret_ref: %s
                %smodels:
                %s""".formatted(
                        accountId, baseUrl, secretRef, accountRateLimit, models));
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
