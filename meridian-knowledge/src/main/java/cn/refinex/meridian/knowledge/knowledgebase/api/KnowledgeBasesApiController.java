package cn.refinex.meridian.knowledge.knowledgebase.api;

import cn.refinex.meridian.api.model.CreateKnowledgeBaseRequest;
import cn.refinex.meridian.api.model.DocumentIngestion;
import cn.refinex.meridian.api.model.DocumentStatus;
import cn.refinex.meridian.api.model.IngestionError;
import cn.refinex.meridian.api.model.IngestionStage;
import cn.refinex.meridian.api.model.KnowledgeBase;
import cn.refinex.meridian.api.model.KnowledgeBasePage;
import cn.refinex.meridian.api.model.KnowledgeDocument;
import cn.refinex.meridian.api.model.KnowledgeDocumentPage;
import cn.refinex.meridian.api.model.KnowledgeSearchRequest;
import cn.refinex.meridian.api.model.KnowledgeSearchResult;
import cn.refinex.meridian.api.model.UpdateKnowledgeBaseRequest;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.crypto.Sha256;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.knowledge.config.KnowledgeProperties;
import cn.refinex.meridian.knowledge.document.app.DocumentDetails;
import cn.refinex.meridian.knowledge.document.app.DocumentService;
import cn.refinex.meridian.knowledge.document.domain.Document;
import cn.refinex.meridian.knowledge.ingestion.domain.IngestionProgress;
import cn.refinex.meridian.knowledge.knowledgebase.app.KnowledgeBaseService;
import cn.refinex.meridian.knowledge.openapi.pub.KnowledgeBasesApi;
import cn.refinex.meridian.knowledge.search.api.SearchDtos;
import cn.refinex.meridian.knowledge.search.app.KnowledgeSearchService;
import cn.refinex.meridian.knowledge.search.app.SearchRequest;
import cn.refinex.meridian.knowledge.web.ApiTimes;
import cn.refinex.meridian.knowledge.web.PublicRequests;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import cn.refinex.meridian.security.apikey.PublicApiRequests;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 公开 API 的 {@code KnowledgeBases} 标签（ADR-0051）：API Key 或控制台用户（带 {@code Meridian-Project}）经 {@code ApiKeyFilter} 认证，
 * {@code PlatformContext} 已绑定为它的项目。读要 {@code knowledge:read}，写要 {@code knowledge:write}；POST 经 {@link PublicRequests}
 * 支持 {@code Idempotency-Key}，上传的指纹是文件名、内容摘要与标签。检索（T19）交给 {@code search.app.KnowledgeSearchService}，读权限即可，
 * 访问标签由调用方在请求体中声明（平台信任 API Key 所属项目声明的标签，架构设计「对外形态」）。
 */
@RestController
public class KnowledgeBasesApiController implements KnowledgeBasesApi {

    private final KnowledgeBaseService bases;
    private final DocumentService documents;
    private final KnowledgeSearchService search;
    private final PublicRequests idempotent;
    private final HttpServletRequest request;
    private final int dimensions;

    /**
     * 创建处理器。
     *
     * @param bases 知识库用例
     * @param documents 文档用例
     * @param search 检索用例
     * @param idempotent POST 的幂等执行
     * @param request 当前请求（Spring 注入的代理）
     * @param properties knowledge 配置，取向量维度
     */
    public KnowledgeBasesApiController(
            KnowledgeBaseService bases,
            DocumentService documents,
            KnowledgeSearchService search,
            PublicRequests idempotent,
            HttpServletRequest request,
            KnowledgeProperties properties) {
        this.bases = bases;
        this.documents = documents;
        this.search = search;
        this.idempotent = idempotent;
        this.request = request;
        this.dimensions = properties.embedding().dimensions();
    }

    @Override
    public ResponseEntity<KnowledgeBase> createKnowledgeBase(CreateKnowledgeBaseRequest body, String idempotencyKey) {
        PublicApiRequests.require(request, ApiKeyScope.KNOWLEDGE_WRITE);
        return idempotent.execute(
                request,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                KnowledgeBase.class,
                () -> toDto(bases.create(
                        body.getName(), body.getDescription(), body.getEmbeddingModel(), body.getRerankModel())));
    }

    @Override
    public ResponseEntity<KnowledgeBasePage> listKnowledgeBases(Integer limit, String after) {
        PublicApiRequests.require(request, ApiKeyScope.KNOWLEDGE_READ);
        PageResult<cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeBase> page =
                bases.list(PageRequest.of(limit, after));
        return ResponseEntity.ok(new KnowledgeBasePage(
                page.data().stream().map(this::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    public ResponseEntity<KnowledgeBase> getKnowledgeBase(String knowledgeBaseId) {
        PublicApiRequests.require(request, ApiKeyScope.KNOWLEDGE_READ);
        return ResponseEntity.ok(toDto(bases.get(knowledgeBaseId)));
    }

    @Override
    public ResponseEntity<KnowledgeBase> updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest body) {
        PublicApiRequests.require(request, ApiKeyScope.KNOWLEDGE_WRITE);
        return ResponseEntity.ok(
                toDto(bases.update(knowledgeBaseId, body.getName(), body.getDescription(), body.getRerankModel())));
    }

    @Override
    public ResponseEntity<Void> deleteKnowledgeBase(String knowledgeBaseId) {
        PublicApiRequests.require(request, ApiKeyScope.KNOWLEDGE_WRITE);
        bases.delete(knowledgeBaseId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<KnowledgeDocument> uploadDocument(
            String knowledgeBaseId, MultipartFile file, String idempotencyKey, String aclTags) {
        PublicApiRequests.require(request, ApiKeyScope.KNOWLEDGE_WRITE);
        Path temp = null;
        try {
            temp = Files.createTempFile("meridian-kb-upload-", ".part");
            file.transferTo(temp);
            Path uploaded = temp;
            Map<String, Object> fingerprint = new LinkedHashMap<>();
            if (idempotencyKey != null) {
                fingerprint.put("filename", String.valueOf(file.getOriginalFilename()));
                fingerprint.put("sha256", Sha256.hex(uploaded));
                fingerprint.put("acl_tags", String.valueOf(aclTags));
            }
            return idempotent.execute(
                    request,
                    idempotencyKey,
                    fingerprint,
                    HttpStatus.ACCEPTED,
                    KnowledgeDocument.class,
                    () -> toDto(documents.upload(knowledgeBaseId, uploaded, file.getOriginalFilename(), aclTags)));
        } catch (IOException e) {
            throw new UncheckedIOException("保存上传内容失败", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时目录中的残留由系统清理
                }
            }
        }
    }

    @Override
    public ResponseEntity<KnowledgeDocumentPage> listDocuments(String knowledgeBaseId, Integer limit, String after) {
        PublicApiRequests.require(request, ApiKeyScope.KNOWLEDGE_READ);
        PageResult<DocumentDetails> page = documents.list(knowledgeBaseId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new KnowledgeDocumentPage(
                page.data().stream().map(KnowledgeBasesApiController::toDto).toList(),
                page.hasMore(),
                page.nextCursor()));
    }

    @Override
    public ResponseEntity<KnowledgeDocument> getDocument(String knowledgeBaseId, String documentId) {
        PublicApiRequests.require(request, ApiKeyScope.KNOWLEDGE_READ);
        return ResponseEntity.ok(toDto(documents.get(knowledgeBaseId, documentId)));
    }

    @Override
    public ResponseEntity<KnowledgeDocument> reprocessDocument(
            String knowledgeBaseId, String documentId, String idempotencyKey) {
        PublicApiRequests.require(request, ApiKeyScope.KNOWLEDGE_WRITE);
        return idempotent.execute(
                request,
                idempotencyKey,
                null,
                HttpStatus.ACCEPTED,
                KnowledgeDocument.class,
                () -> toDto(documents.reprocess(knowledgeBaseId, documentId)));
    }

    @Override
    public ResponseEntity<Void> deleteDocument(String knowledgeBaseId, String documentId) {
        PublicApiRequests.require(request, ApiKeyScope.KNOWLEDGE_WRITE);
        documents.delete(knowledgeBaseId, documentId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<KnowledgeSearchResult> searchKnowledgeBase(
            String knowledgeBaseId, KnowledgeSearchRequest body, String idempotencyKey) {
        PublicApiRequests.require(request, ApiKeyScope.KNOWLEDGE_READ);
        SearchDtos.requireQuery(body.getQuery());
        return idempotent.execute(
                request,
                idempotencyKey,
                body,
                HttpStatus.OK,
                KnowledgeSearchResult.class,
                () -> SearchDtos.toPublic(search.search(new SearchRequest(
                        List.of(knowledgeBaseId),
                        body.getQuery(),
                        body.getTopK() == null ? search.defaultTopK() : body.getTopK(),
                        body.getAclTags(),
                        null,
                        new SearchRequest.Attribution(
                                SearchRequest.Attribution.REST,
                                PlatformContexts.current().apiKeyId(),
                                null,
                                null)))));
    }

    private KnowledgeBase toDto(cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeBase base) {
        return new KnowledgeBase()
                .id(base.id())
                .name(base.name())
                .description(base.description())
                .embeddingModel(base.embeddingModel())
                .rerankModel(base.rerankModel())
                .embeddingDimensions(dimensions)
                .documentCount(base.documentCount())
                .createdAt(ApiTimes.utc(base.createdAt()))
                .updatedAt(ApiTimes.utc(base.updatedAt()));
    }

    private static KnowledgeDocument toDto(DocumentDetails details) {
        Document document = details.document();
        return new KnowledgeDocument()
                .id(document.id())
                .knowledgeBaseId(document.knowledgeBaseId())
                .filename(document.filename())
                .mediaType(document.mediaType())
                .sizeBytes(document.sizeBytes())
                .sha256(document.sha256())
                .aclTags(document.aclTags())
                .status(DocumentStatus.fromValue(document.status().value()))
                .parser(document.parser())
                .chunkCount(document.chunkCount())
                .ingestion(toDto(details.progress()))
                .createdAt(ApiTimes.utc(document.createdAt()))
                .updatedAt(ApiTimes.utc(document.updatedAt()));
    }

    private static DocumentIngestion toDto(IngestionProgress progress) {
        if (progress == null) {
            return new DocumentIngestion().attempts(0).maxAttempts(0);
        }
        return new DocumentIngestion()
                .attempts(progress.attempts())
                .maxAttempts(progress.maxAttempts())
                .stage(
                        progress.stage() == null
                                ? null
                                : IngestionStage.fromValue(progress.stage().value()))
                .chunksTotal(progress.chunksTotal())
                .chunksEmbedded(progress.chunksEmbedded())
                .nextAttemptAt(progress.waitingForRetry() ? ApiTimes.utc(progress.notBefore()) : null)
                .error(
                        progress.errorCode() == null
                                ? null
                                : new IngestionError()
                                        .code(progress.errorCode())
                                        .message(progress.errorMessage())
                                        .retryable(Boolean.TRUE.equals(progress.errorRetryable())))
                .startedAt(ApiTimes.utc(progress.startedAt()))
                .finishedAt(ApiTimes.utc(progress.finishedAt()));
    }
}
