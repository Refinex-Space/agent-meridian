package cn.refinex.meridian.knowledge.document.app;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.crypto.Sha256;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.common.text.FileNames;
import cn.refinex.meridian.knowledge.audit.app.KnowledgeAudit;
import cn.refinex.meridian.knowledge.config.KnowledgeProperties;
import cn.refinex.meridian.knowledge.document.domain.AclTags;
import cn.refinex.meridian.knowledge.document.domain.Document;
import cn.refinex.meridian.knowledge.document.domain.DocumentFormat;
import cn.refinex.meridian.knowledge.document.domain.DocumentStatus;
import cn.refinex.meridian.knowledge.document.infra.DocumentRepository;
import cn.refinex.meridian.knowledge.ingestion.app.IngestionWorker;
import cn.refinex.meridian.knowledge.ingestion.infra.IngestionJobRepository;
import cn.refinex.meridian.knowledge.knowledgebase.app.KnowledgeBaseService;
import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeBase;
import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeErrorCode;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/**
 * 文档的用例（架构设计「写入流程」第 1 步，ADR-0051）。公开 API 的调用在 {@code ApiKeyFilter} 绑定的 {@code PlatformContext} 中执行。
 *
 * <ul>
 *   <li>上传：校验知识库、标签与大小 → 按内容检测媒体类型（Tika，不采用客户端声明的类型），不支持的类型 400 → 原文件写入对象存储 →
 *       同一事务插入文档、排队的写入作业与审计；事务失败时删除刚写入的对象。提交后唤醒本节点的 Worker。
 *   <li>重新处理：文档已有未结束的作业时 409；否则排队一个新作业，旧切片保留到新切片就绪。
 *   <li>删除：删除文档行（切片与作业经外键级联），提交后删除对象存储中的原文件。
 * </ul>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final KnowledgeBaseService bases;
    private final DocumentRepository documents;
    private final IngestionJobRepository jobs;
    private final ObjectStore objects;
    private final KnowledgeAudit audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ObjectProvider<IngestionWorker> worker;
    private final DataSize maxSize;
    private final int maxAttempts;
    private final Tika tika = new Tika();

    /**
     * 创建用例。
     *
     * @param bases 知识库用例
     * @param documents 文档仓储
     * @param jobs 写入作业仓储
     * @param objects 对象存储
     * @param audit 审计
     * @param transactions 事务模板
     * @param clock 时钟
     * @param worker 本节点的 Worker；没有启用写入作业时不存在
     * @param properties knowledge 配置
     */
    public DocumentService(
            KnowledgeBaseService bases,
            DocumentRepository documents,
            IngestionJobRepository jobs,
            ObjectStore objects,
            KnowledgeAudit audit,
            TransactionTemplate transactions,
            Clock clock,
            ObjectProvider<IngestionWorker> worker,
            KnowledgeProperties properties) {
        this.bases = bases;
        this.documents = documents;
        this.jobs = jobs;
        this.objects = objects;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
        this.worker = worker;
        this.maxSize = properties.documents().maxSize();
        this.maxAttempts = properties.ingestion().maxAttempts();
    }

    /**
     * 上传文档，排队写入。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param file 上传内容的本机临时文件（调用方负责删除）
     * @param clientFilename 表单中的文件名；可为空
     * @param aclTags 逗号分隔的访问标签；可为空
     * @return 文档与排队的作业
     * @throws NotFoundException 知识库不存在时
     * @throws ValidationException 空文件、标签不合法、类型不支持时
     * @throws MeridianException 超过大小上限时（413 {@code document.too_large}）
     */
    public DocumentDetails upload(String knowledgeBaseId, Path file, String clientFilename, String aclTags) {
        KnowledgeBase base = bases.get(knowledgeBaseId);
        List<String> tags = AclTags.parse(aclTags);
        long size = size(file);
        if (size == 0) {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED, "文档不能为空", List.of(new FieldViolation("file", "文件为空")));
        }
        if (size > maxSize.toBytes()) {
            throw new MeridianException(KnowledgeErrorCode.TOO_LARGE, "文档超过大小上限 " + maxSize.toMegabytes() + " MB");
        }
        String filename = FileNames.normalize(clientFilename);
        String mediaType = detect(file, filename);
        if (DocumentFormat.of(mediaType).isEmpty()) {
            throw new MeridianException(KnowledgeErrorCode.UNSUPPORTED_TYPE, "不支持的文档类型：" + mediaType);
        }
        PlatformContext context = PlatformContexts.current();
        Instant now = clock.instant();
        String id = Ids.newId(IdPrefix.DOCUMENT);
        String key = Document.objectKey(context.tenantId(), context.projectId(), base.id(), id);
        Document document = new Document(
                id,
                context.tenantId(),
                context.projectId(),
                base.id(),
                filename,
                mediaType,
                size,
                Sha256.hex(file),
                key,
                tags,
                DocumentStatus.QUEUED,
                null,
                0,
                0,
                context.actor(),
                now,
                now);
        try (InputStream in = Files.newInputStream(file)) {
            objects.put(key, in, size, mediaType);
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传的临时文件失败", e);
        }
        try {
            transactions.executeWithoutResult(status -> {
                documents.insert(document);
                jobs.enqueue(Ids.newId(IdPrefix.INGESTION_JOB), document.id(), maxAttempts, now);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("knowledge_base_id", base.id());
                details.put("filename", filename);
                details.put("media_type", mediaType);
                details.put("size_bytes", size);
                details.put("acl_tags", tags);
                audit.record("document.uploaded", "document", document.id(), details);
                wakeAfterCommit();
            });
        } catch (RuntimeException e) {
            deleteObject(key);
            throw e;
        }
        return details(document);
    }

    /**
     * 查看文档与写入进度。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param id 文档 ID
     * @return 文档与进度
     * @throws NotFoundException 知识库或文档不存在时
     */
    public DocumentDetails get(String knowledgeBaseId, String id) {
        bases.get(knowledgeBaseId);
        return details(require(knowledgeBaseId, id, false));
    }

    /**
     * 分页列出知识库中的文档。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param page 分页参数
     * @return 一页文档
     * @throws NotFoundException 知识库不存在时
     */
    public PageResult<DocumentDetails> list(String knowledgeBaseId, PageRequest page) {
        bases.get(knowledgeBaseId);
        PageResult<Document> rows =
                PageResult.of(documents.list(knowledgeBaseId, page), page, document -> Cursor.of(document.id()));
        var progress = jobs.latest(rows.data().stream().map(Document::id).toList());
        return rows.map(document -> new DocumentDetails(document, progress.get(document.id())));
    }

    /**
     * 重新处理文档：用原文件重新写入，新切片就绪前旧切片仍可检索。写审计 {@code document.reprocessed}。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param id 文档 ID
     * @return 文档与排队的作业
     * @throws NotFoundException 知识库或文档不存在时
     * @throws ConflictException 写入正在排队或进行时（{@code document.processing}）
     */
    public DocumentDetails reprocess(String knowledgeBaseId, String id) {
        bases.get(knowledgeBaseId);
        Document document = transactions.execute(status -> {
            Document locked = require(knowledgeBaseId, id, true);
            Instant now = clock.instant();
            if (jobs.enqueue(Ids.newId(IdPrefix.INGESTION_JOB), locked.id(), maxAttempts, now) == 0) {
                throw new ConflictException(KnowledgeErrorCode.PROCESSING, "文档正在写入，不能重新处理：" + id);
            }
            documents.updateStatus(locked.id(), DocumentStatus.QUEUED, now);
            audit.record("document.reprocessed", "document", locked.id(), Map.of("knowledge_base_id", knowledgeBaseId));
            wakeAfterCommit();
            return locked;
        });
        return details(require(knowledgeBaseId, document.id(), false));
    }

    /**
     * 删除文档、它的全部切片与原文件，写审计 {@code document.deleted}。正在进行的写入在结束事务中发现文档已删除，结果被丢弃。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param id 文档 ID
     * @throws NotFoundException 知识库或文档不存在时
     */
    public void delete(String knowledgeBaseId, String id) {
        bases.get(knowledgeBaseId);
        Document deleted = transactions.execute(status -> {
            Document locked = require(knowledgeBaseId, id, true);
            documents.delete(locked.id());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("knowledge_base_id", knowledgeBaseId);
            details.put("filename", locked.filename());
            details.put("chunk_count", locked.chunkCount());
            audit.record("document.deleted", "document", locked.id(), details);
            return locked;
        });
        deleteObject(deleted.objectKey());
    }

    private DocumentDetails details(Document document) {
        return new DocumentDetails(document, jobs.latest(List.of(document.id())).get(document.id()));
    }

    private Document require(String knowledgeBaseId, String id, boolean forUpdate) {
        return documents
                .find(knowledgeBaseId, id, forUpdate)
                .orElseThrow(() -> new NotFoundException(KnowledgeErrorCode.DOCUMENT_NOT_FOUND, "文档不存在：" + id));
    }

    private void wakeAfterCommit() {
        IngestionWorker local = worker.getIfAvailable();
        if (local != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    local.wakeUp();
                }
            });
        }
    }

    private void deleteObject(String key) {
        try {
            objects.delete(key);
        } catch (RuntimeException e) {
            // 文档行已经删除（或没有插入），残留的对象不再被引用
            log.warn("原文件删除失败，对象残留 key={}", key, e);
        }
    }

    private String detect(Path file, String filename) {
        try (InputStream in = Files.newInputStream(file)) {
            return tika.detect(in, filename);
        } catch (IOException e) {
            throw new UncheckedIOException("检测媒体类型失败", e);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传的临时文件失败", e);
        }
    }
}
