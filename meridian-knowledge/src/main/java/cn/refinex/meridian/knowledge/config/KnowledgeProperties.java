package cn.refinex.meridian.knowledge.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * knowledge 的配置（前缀 {@code meridian.knowledge}，ADR-0051）。
 *
 * @param documents 文档上传
 * @param embedding 向量
 * @param chunking 切分
 * @param ingestion 写入作业
 * @param docParser doc-parser 服务
 * @param runtime runtime 服务
 * @param storageMetering 存储计量
 */
@ConfigurationProperties("meridian.knowledge")
public record KnowledgeProperties(
        @DefaultValue Documents documents,
        @DefaultValue Embedding embedding,
        @DefaultValue Chunking chunking,
        @DefaultValue Ingestion ingestion,
        @DefaultValue DocParser docParser,
        @DefaultValue Runtime runtime,
        @DefaultValue StorageMetering storageMetering) {

    /**
     * 文档上传。
     *
     * @param maxSize 单个文档的大小上限，默认 64 MB（公开 API 规范写明的默认值）；multipart 的上限按它设置
     */
    public record Documents(@DefaultValue("64MB") DataSize maxSize) {}

    /**
     * 向量。
     *
     * @param dimensions 向量维度，默认 1024（{@code qwen3.7-text-embedding} 的维度）；迁移以 Flyway 占位符
     *     {@code embedding_dimensions} 建 {@code kb.chunks.embedding}，启动时核对两者一致
     * @param batchSize 每次调用 runtime 的条数，默认 64（架构设计「写入流程」），不能超过 64
     */
    public record Embedding(
            @DefaultValue("1024") int dimensions,
            @DefaultValue("64") int batchSize) {}

    /**
     * 切分（架构设计「写入流程」第 3 步）。
     *
     * @param targetTokens 正文切片的目标长度（上限），默认 512 token
     * @param overlapTokens 相邻切片的重叠，默认 64 token
     * @param maxTableTokens 表格整体保留的上限，超过时按行拆分并重复表头，默认 2048 token（ADR-0051）
     */
    public record Chunking(
            @DefaultValue("512") int targetTokens,
            @DefaultValue("64") int overlapTokens,
            @DefaultValue("2048") int maxTableTokens) {}

    /**
     * 写入作业（ADR-0051）。
     *
     * @param enabled 是否在本实例运行写入作业，默认开启
     * @param workers 本实例同时执行的作业数，默认 2
     * @param pollInterval 没有作业时的轮询间隔，默认 2 秒
     * @param lease 认领时写的租约时长，默认 5 分钟
     * @param heartbeat 执行中续租的间隔，默认 1 分钟
     * @param maxAttempts 最多尝试次数，默认 4（首次加架构设计规定的 3 次重试）
     * @param backoffBase 第一次重试的等待，之后每次翻倍，默认 30 秒
     * @param nodeId 本实例的标识，写进租约；为空时按主机名生成
     */
    public record Ingestion(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2") int workers,
            @DefaultValue("2s") Duration pollInterval,
            @DefaultValue("5m") Duration lease,
            @DefaultValue("1m") Duration heartbeat,
            @DefaultValue("4") int maxAttempts,
            @DefaultValue("30s") Duration backoffBase,
            String nodeId) {}

    /**
     * doc-parser 服务。
     *
     * @param url 基础 URL，例如 {@code http://doc-parser:8000}；为空表示没有部署，需要它的文档写入失败（不重试）
     * @param timeout 单次解析的读取超时，默认 15 分钟：CPU 上识别表格结构、逐页 OCR 都慢（doc-parser 的 README 有实测）
     */
    public record DocParser(String url, @DefaultValue("15m") Duration timeout) {}

    /**
     * runtime 服务。
     *
     * @param url 基础 URL，例如 {@code http://runtime:8080}；Embedding 经它的内部接口调用，没有配置时写入在 Embedding 阶段失败
     */
    public record Runtime(String url) {}

    /**
     * 存储计量（ADR-0051）。
     *
     * @param enabled 是否在本实例上报，默认开启；多实例时上报靠 {@code kb.storage_reports} 去重
     * @param interval 检查间隔，默认 1 小时：每次检查上报前一天（UTC）还没有上报的知识库
     */
    public record StorageMetering(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1h") Duration interval) {}
}
