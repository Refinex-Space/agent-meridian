package cn.refinex.meridian.knowledge.ingestion.app;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.knowledge.config.KnowledgeProperties;
import cn.refinex.meridian.knowledge.document.domain.Document;
import cn.refinex.meridian.knowledge.document.infra.DocumentRepository;
import cn.refinex.meridian.knowledge.ingestion.domain.ChunkDraft;
import cn.refinex.meridian.knowledge.ingestion.domain.Chunker;
import cn.refinex.meridian.knowledge.ingestion.domain.ClaimedJob;
import cn.refinex.meridian.knowledge.ingestion.domain.IngestionFailure;
import cn.refinex.meridian.knowledge.ingestion.domain.IngestionStage;
import cn.refinex.meridian.knowledge.ingestion.domain.ParsedDocument;
import cn.refinex.meridian.knowledge.ingestion.domain.RetrySchedule;
import cn.refinex.meridian.knowledge.ingestion.domain.SourceLocator;
import cn.refinex.meridian.knowledge.ingestion.infra.ChunkRepository;
import cn.refinex.meridian.knowledge.ingestion.infra.system.IngestionQueueRepository;
import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeBase;
import cn.refinex.meridian.knowledge.knowledgebase.infra.KnowledgeBaseRepository;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 执行一次写入尝试（架构设计「写入流程」，ADR-0051）：下载原文件 → 解析 → 切分 → 按批计算向量 → 一个事务内替换文档的切片。
 *
 * <p>每个阶段开始时以 {@code (作业, 尝试次数)} 为条件记录进度，条件不成立（被其他节点收回）时放弃本次尝试、不写任何数据。结束事务先锁住
 * 文档与作业行再写：文档在执行期间被删除时，结果被丢弃。失败按 {@link RetrySchedule} 退避重试或结束作业。
 *
 * <p>仓储在本次作业的租户与项目的 {@code PlatformContext} 中执行。线程安全：无状态，多个作业并发执行。
 */
@Service
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final IngestionQueueRepository queue;
    private final DocumentRepository documents;
    private final KnowledgeBaseRepository bases;
    private final ChunkRepository chunks;
    private final ObjectStore objects;
    private final DocumentParsing parsing;
    private final Embeddings embeddings;
    private final Chunker chunker;
    private final RetrySchedule retries;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int dimensions;
    private final int batchSize;

    /**
     * 创建流水线。
     *
     * @param queue 作业队列
     * @param documents 文档仓储
     * @param bases 知识库仓储
     * @param chunks 切片仓储
     * @param objects 对象存储
     * @param parsing 解析器的路由
     * @param embeddings 向量计算
     * @param chunker 切分器
     * @param retries 重试计划
     * @param transactions 事务模板
     * @param clock 时钟
     * @param properties knowledge 配置，取向量维度与每批条数
     */
    public IngestionPipeline(
            IngestionQueueRepository queue,
            DocumentRepository documents,
            KnowledgeBaseRepository bases,
            ChunkRepository chunks,
            ObjectStore objects,
            DocumentParsing parsing,
            Embeddings embeddings,
            Chunker chunker,
            RetrySchedule retries,
            TransactionTemplate transactions,
            Clock clock,
            KnowledgeProperties properties) {
        this.queue = queue;
        this.documents = documents;
        this.bases = bases;
        this.chunks = chunks;
        this.objects = objects;
        this.parsing = parsing;
        this.embeddings = embeddings;
        this.chunker = chunker;
        this.retries = retries;
        this.transactions = transactions;
        this.clock = clock;
        this.dimensions = properties.embedding().dimensions();
        this.batchSize = properties.embedding().batchSize();
    }

    /**
     * 执行一次尝试。异常不会抛出：失败记进作业，未预期的错误按可重试处理。
     *
     * @param job 本节点认领的尝试
     */
    public void run(ClaimedJob job) {
        PlatformContext context = PlatformContext.builder()
                .tenantId(job.tenantId())
                .projectId(job.projectId())
                .build();
        PlatformContexts.run(context, () -> {
            try {
                execute(job);
            } catch (Abandoned abandoned) {
                log.info("写入尝试已被收回或文档已删除，放弃 jobId={} attempt={}", job.jobId(), job.attempt());
            } catch (IngestionFailure failure) {
                recordFailure(job, failure);
            } catch (RuntimeException e) {
                log.error("写入出现未预期的错误 jobId={} documentId={}", job.jobId(), job.documentId(), e);
                recordFailure(job, new IngestionFailure("internal.error", "内部错误", true, e));
            }
        });
    }

    private void execute(ClaimedJob job) {
        Document document = documents.findById(job.documentId()).orElseThrow(Abandoned::new);
        KnowledgeBase base = bases.find(document.knowledgeBaseId()).orElseThrow(Abandoned::new);
        progress(job, IngestionStage.PARSING, null, null);
        Path file = download(document);
        ParsedDocument parsed;
        try {
            parsed = parsing.parse(file, document);
        } finally {
            deleteQuietly(file);
        }
        progress(job, IngestionStage.CHUNKING, null, null);
        List<ChunkDraft> drafts = chunker.split(parsed);
        if (drafts.isEmpty()) {
            throw IngestionFailure.permanent(IngestionFailure.NO_TEXT, "文档中没有可以检索的文字");
        }
        progress(job, IngestionStage.EMBEDDING, drafts.size(), 0);
        List<float[]> vectors = new ArrayList<>(drafts.size());
        for (int from = 0; from < drafts.size(); from += batchSize) {
            List<ChunkDraft> batch = drafts.subList(from, Math.min(from + batchSize, drafts.size()));
            List<float[]> embedded = embeddings.embed(
                    job.tenantId(),
                    job.projectId(),
                    base.embeddingModel(),
                    batch.stream().map(ChunkDraft::searchText).toList(),
                    dimensions);
            for (float[] vector : embedded) {
                if (vector.length != dimensions) {
                    throw IngestionFailure.permanent(
                            IngestionFailure.DIMENSION_MISMATCH,
                            "模型 " + base.embeddingModel() + " 返回 " + vector.length + " 维向量，知识库要求 " + dimensions + " 维");
                }
            }
            vectors.addAll(embedded);
            progress(job, IngestionStage.EMBEDDING, drafts.size(), vectors.size());
        }
        progress(job, IngestionStage.STORING, drafts.size(), vectors.size());
        store(job, parsed.parser(), drafts, vectors);
    }

    /** 一个事务内替换切片并结束作业。 */
    private void store(ClaimedJob job, String parser, List<ChunkDraft> drafts, List<float[]> vectors) {
        boolean stored = Boolean.TRUE.equals(transactions.execute(status -> {
            Optional<Document> locked = queue.lockForFinish(job);
            if (locked.isEmpty()) {
                return false;
            }
            Document document = locked.get();
            Instant now = clock.instant();
            chunks.deleteByDocument(document.id());
            long indexBytes = 0;
            for (int i = 0; i < drafts.size(); i++) {
                ChunkDraft draft = drafts.get(i);
                chunks.insert(
                        Ids.newId(IdPrefix.CHUNK),
                        document.knowledgeBaseId(),
                        document.id(),
                        draft,
                        vectors.get(i),
                        document.aclTags(),
                        new SourceLocator(
                                document.id(),
                                document.filename(),
                                draft.pageStart(),
                                draft.pageEnd(),
                                draft.headingPath()),
                        now);
                indexBytes += draft.content().getBytes(StandardCharsets.UTF_8).length + (long) Float.BYTES * dimensions;
            }
            queue.succeed(job, parser, drafts.size(), indexBytes, now);
            return true;
        }));
        if (!stored) {
            throw new Abandoned();
        }
        log.info(
                "文档写入完成 documentId={} jobId={} attempt={} parser={} chunks={}",
                job.documentId(),
                job.jobId(),
                job.attempt(),
                parser,
                drafts.size());
    }

    private void recordFailure(ClaimedJob job, IngestionFailure failure) {
        transactions.executeWithoutResult(status -> {
            if (queue.lockForFinish(job).isEmpty()) {
                return;
            }
            Instant now = clock.instant();
            Optional<Instant> next = retries.next(job.attempt(), failure.retryable(), now);
            if (next.isPresent()) {
                queue.retryLater(job, next.get(), failure.code(), failure.getMessage(), now);
            } else {
                queue.fail(job, failure.code(), failure.getMessage(), failure.retryable(), now);
            }
            log.warn(
                    "文档写入失败 documentId={} jobId={} attempt={} code={} retryable={} nextAttemptAt={}",
                    job.documentId(),
                    job.jobId(),
                    job.attempt(),
                    failure.code(),
                    failure.retryable(),
                    next.orElse(null));
        });
    }

    private void progress(ClaimedJob job, IngestionStage stage, Integer total, Integer embedded) {
        if (!queue.progress(job, stage, total, embedded, clock.instant())) {
            throw new Abandoned();
        }
    }

    private Path download(Document document) {
        Optional<InputStream> stream;
        try {
            stream = objects.open(document.objectKey());
        } catch (RuntimeException e) {
            throw IngestionFailure.unavailable("对象存储暂时不可用", e);
        }
        if (stream.isEmpty()) {
            throw IngestionFailure.permanent(IngestionFailure.PARSE_FAILED, "对象存储中没有原文件");
        }
        try (InputStream in = stream.get()) {
            Path file = Files.createTempFile("meridian-kb-", ".doc");
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        } catch (IOException | UncheckedIOException e) {
            throw IngestionFailure.unavailable("下载原文件失败", e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("临时文件删除失败 file={}", file, e);
        }
    }

    /** 本次尝试被收回或文档已删除：不写任何结果。 */
    private static final class Abandoned extends RuntimeException {

        Abandoned() {
            super(null, null, false, false);
        }
    }
}
