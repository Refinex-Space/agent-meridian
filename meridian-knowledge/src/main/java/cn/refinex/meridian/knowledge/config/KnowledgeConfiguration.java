package cn.refinex.meridian.knowledge.config;

import cn.refinex.meridian.knowledge.ingestion.app.Embeddings;
import cn.refinex.meridian.knowledge.ingestion.app.IngestionPipeline;
import cn.refinex.meridian.knowledge.ingestion.app.IngestionWorker;
import cn.refinex.meridian.knowledge.ingestion.app.LayoutParser;
import cn.refinex.meridian.knowledge.ingestion.domain.Chunker;
import cn.refinex.meridian.knowledge.ingestion.domain.RetrySchedule;
import cn.refinex.meridian.knowledge.ingestion.infra.DocParserClient;
import cn.refinex.meridian.knowledge.ingestion.infra.RuntimeEmbeddings;
import cn.refinex.meridian.knowledge.ingestion.infra.system.IngestionQueueRepository;
import cn.refinex.meridian.knowledge.ingestion.infra.system.SchemaInspection;
import cn.refinex.meridian.knowledge.metering.app.StorageMetering;
import cn.refinex.meridian.knowledge.web.ApiKeyRevocationListener;
import cn.refinex.meridian.modelclient.EmbeddingClient;
import cn.refinex.meridian.persistence.jdbc.PeriodicJob;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import cn.refinex.meridian.security.apikey.ApiKeyResolver;
import cn.refinex.meridian.security.apikey.ControlPlaneApiKeyResolver;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.security.servicejwt.ServiceRestClients;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * knowledge 的装配（ADR-0051）：切分与重试参数、doc-parser 与 runtime 的客户端、写入作业的 Worker 与后台任务、存储计量的后台任务、
 * 迁移的向量维度占位符与启动时的维度核对、{@code key.revoked} 的订阅。
 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeConfiguration {

    /** Flyway 占位符：{@code kb.chunks.embedding} 的维度。 */
    static final String DIMENSIONS_PLACEHOLDER = "embedding_dimensions";

    /** 调 doc-parser 的连接超时。 */
    private static final Duration DOC_PARSER_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 本服务的时钟。
     *
     * @return 系统 UTC 时钟
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 迁移的占位符：向量维度取配置。
     *
     * @param properties knowledge 配置
     * @return Flyway 定制器
     */
    @Bean
    public FlywayConfigurationCustomizer embeddingDimensionsPlaceholder(KnowledgeProperties properties) {
        return configuration -> configuration.placeholders(Map.of(
                DIMENSIONS_PLACEHOLDER, Integer.toString(properties.embedding().dimensions())));
    }

    /**
     * 启动时核对 {@code kb.chunks.embedding} 的维度与配置一致：迁移按建表时的配置执行，之后改了配置会写不进向量。
     *
     * @param schema 表结构查询
     * @param properties knowledge 配置
     * @return 启动检查
     */
    @Bean
    public SmartInitializingSingleton embeddingDimensionsCheck(
            SchemaInspection schema, KnowledgeProperties properties) {
        return () -> {
            int actual = schema.embeddingDimensions();
            int configured = properties.embedding().dimensions();
            if (actual != configured) {
                throw new IllegalStateException("kb.chunks.embedding 是 " + actual
                        + " 维，与配置 meridian.knowledge.embedding.dimensions=" + configured + " 不一致：换维度要新建迁移重建该列与索引");
            }
        };
    }

    /**
     * 切分器。
     *
     * @param properties knowledge 配置
     * @return 切分器
     */
    @Bean
    public Chunker chunker(KnowledgeProperties properties) {
        KnowledgeProperties.Chunking chunking = properties.chunking();
        return new Chunker(
                new Chunker.Settings(chunking.targetTokens(), chunking.overlapTokens(), chunking.maxTableTokens()));
    }

    /**
     * 写入作业的重试计划。
     *
     * @param properties knowledge 配置
     * @return 重试计划
     */
    @Bean
    public RetrySchedule retrySchedule(KnowledgeProperties properties) {
        return new RetrySchedule(
                properties.ingestion().maxAttempts(), properties.ingestion().backoffBase());
    }

    /**
     * doc-parser 客户端；没有配置地址时，需要它的文档写入失败。
     *
     * @param properties knowledge 配置
     * @return 版面解析
     */
    @Bean
    public LayoutParser layoutParser(KnowledgeProperties properties) {
        return new DocParserClient(
                properties.docParser().url(),
                DOC_PARSER_CONNECT_TIMEOUT,
                properties.docParser().timeout());
    }

    /**
     * 经 runtime 的 Embedding：带 knowledge 的服务 JWT（{@code aud} 为 runtime）。
     *
     * @param properties knowledge 配置
     * @param clients 服务间调用客户端的构造入口
     * @return 向量计算
     * @throws IllegalStateException 每批条数超过 64 时
     */
    @Bean
    public Embeddings embeddings(KnowledgeProperties properties, ServiceRestClients clients) {
        if (properties.embedding().batchSize() < 1 || properties.embedding().batchSize() > EmbeddingClient.MAX_BATCH) {
            throw new IllegalStateException(
                    "meridian.knowledge.embedding.batch-size 必须在 1 至 " + EmbeddingClient.MAX_BATCH + " 之间");
        }
        String url = properties.runtime().url();
        if (url == null || url.isBlank()) {
            return new RuntimeEmbeddings(null);
        }
        return new RuntimeEmbeddings(new EmbeddingClient(clients.create(url, ServiceJwts.RUNTIME)));
    }

    /**
     * 本实例的写入作业 Worker。
     *
     * @param queue 作业队列
     * @param pipeline 流水线
     * @param retries 重试计划
     * @param transactions 事务模板
     * @param clock 时钟
     * @param properties knowledge 配置
     * @return Worker；容器关闭时停止
     */
    @Bean
    @ConditionalOnProperty(name = "meridian.knowledge.ingestion.enabled", havingValue = "true", matchIfMissing = true)
    public IngestionWorker ingestionWorker(
            IngestionQueueRepository queue,
            IngestionPipeline pipeline,
            RetrySchedule retries,
            TransactionTemplate transactions,
            Clock clock,
            KnowledgeProperties properties) {
        KnowledgeProperties.Ingestion ingestion = properties.ingestion();
        return new IngestionWorker(
                queue,
                pipeline,
                retries,
                transactions,
                clock,
                nodeId(ingestion.nodeId()),
                ingestion.lease(),
                ingestion.workers());
    }

    /**
     * 写入作业的轮询：收回过期租约、认领到期的作业。
     *
     * @param worker Worker
     * @param properties knowledge 配置
     * @return 后台任务
     */
    @Bean
    @ConditionalOnProperty(name = "meridian.knowledge.ingestion.enabled", havingValue = "true", matchIfMissing = true)
    public PeriodicJob ingestionPollJob(IngestionWorker worker, KnowledgeProperties properties) {
        return new PeriodicJob("ingestion-poll", properties.ingestion().pollInterval(), worker::poll);
    }

    /**
     * 写入作业的续租。阶段比轮询小：容器停止时先停轮询、后停续租。
     *
     * @param worker Worker
     * @param properties knowledge 配置
     * @return 后台任务
     */
    @Bean
    @ConditionalOnProperty(name = "meridian.knowledge.ingestion.enabled", havingValue = "true", matchIfMissing = true)
    public PeriodicJob ingestionLeaseJob(IngestionWorker worker, KnowledgeProperties properties) {
        return new PeriodicJob(
                "ingestion-lease", properties.ingestion().heartbeat(), worker::renew, PeriodicJob.DEFAULT_PHASE - 1);
    }

    /**
     * 每日存储计量。
     *
     * @param metering 存储计量
     * @param properties knowledge 配置
     * @return 后台任务
     */
    @Bean
    @ConditionalOnProperty(
            name = "meridian.knowledge.storage-metering.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public PeriodicJob storageMeteringJob(StorageMetering metering, KnowledgeProperties properties) {
        return new PeriodicJob("storage-metering", properties.storageMetering().interval(), metering::reportYesterday);
    }

    /**
     * 订阅 API Key 吊销：只在经 control-plane 校验 API Key（带缓存）时需要。
     *
     * @param redis Redis 消息封装
     * @param resolver API Key 解析器
     * @return 订阅者；解析器不是 control-plane 的实现时（测试的开发期 Key）不订阅
     */
    @Bean
    public ApiKeyRevocationListener apiKeyRevocationListener(RedisMessaging redis, ApiKeyResolver resolver) {
        return new ApiKeyRevocationListener(
                redis, resolver instanceof ControlPlaneApiKeyResolver cached ? cached : null);
    }

    private static String nodeId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        try {
            return InetAddress.getLocalHost().getHostName() + "-"
                    + ProcessHandle.current().pid();
        } catch (UnknownHostException e) {
            return "knowledge-" + ProcessHandle.current().pid();
        }
    }
}
