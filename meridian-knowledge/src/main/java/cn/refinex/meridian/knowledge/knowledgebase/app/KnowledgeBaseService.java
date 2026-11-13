package cn.refinex.meridian.knowledge.knowledgebase.app;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.knowledge.audit.app.KnowledgeAudit;
import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeBase;
import cn.refinex.meridian.knowledge.knowledgebase.domain.KnowledgeErrorCode;
import cn.refinex.meridian.knowledge.knowledgebase.infra.KnowledgeBaseRepository;
import cn.refinex.meridian.knowledge.knowledgebase.infra.system.KnowledgeBaseLookupRepository;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 知识库的用例（ADR-0051）。公开 API 的调用在 {@code ApiKeyFilter} 绑定的 {@code PlatformContext}（租户与项目）中执行。
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final KnowledgeBaseRepository bases;
    private final KnowledgeBaseLookupRepository lookup;
    private final KnowledgeAudit audit;
    private final ObjectStore objects;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param bases 项目级仓储
     * @param lookup 跨租户的按 ID 查找
     * @param audit 审计
     * @param objects 对象存储，删除知识库时删除原文件
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public KnowledgeBaseService(
            KnowledgeBaseRepository bases,
            KnowledgeBaseLookupRepository lookup,
            KnowledgeAudit audit,
            ObjectStore objects,
            TransactionTemplate transactions,
            Clock clock) {
        this.bases = bases;
        this.lookup = lookup;
        this.audit = audit;
        this.objects = objects;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 创建知识库，写审计 {@code knowledge_base.created}。Embedding 模型是否存在在写入第一份文档时由 runtime 校验，rerank 模型在第一次
     * 检索时校验。
     *
     * @param name 名称
     * @param description 说明；可为空
     * @param embeddingModel Embedding 模型 ID
     * @param rerankModel rerank 模型 ID；可为空，表示检索时不重排
     * @return 新建的知识库
     */
    public KnowledgeBase create(String name, String description, String embeddingModel, String rerankModel) {
        PlatformContext context = PlatformContexts.current();
        Instant now = clock.instant();
        KnowledgeBase base = new KnowledgeBase(
                Ids.newId(IdPrefix.KNOWLEDGE_BASE),
                context.tenantId(),
                context.projectId(),
                name.strip(),
                description == null || description.isBlank() ? null : description.strip(),
                embeddingModel,
                rerankModel == null || rerankModel.isBlank() ? null : rerankModel,
                0,
                context.actor(),
                now,
                now);
        transactions.executeWithoutResult(status -> {
            bases.insert(base);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("name", base.name());
            details.put("embedding_model", base.embeddingModel());
            details.put("rerank_model", base.rerankModel());
            audit.record("knowledge_base.created", "knowledge_base", base.id(), details);
        });
        return base;
    }

    /**
     * 修改名称、说明与 rerank 模型，只改给出的字段（D81，ADR-0053）；说明与 rerank 模型给空白表示清除。Embedding 模型不能改。
     *
     * @param id 知识库 ID
     * @param name 新名称；为空表示不变
     * @param description 新说明；为空表示不变，空白表示清除
     * @param rerankModel 新 rerank 模型；为空表示不变，空白表示清除
     * @return 修改后的知识库
     * @throws NotFoundException 不存在或不属于当前项目时
     */
    public KnowledgeBase update(String id, String name, String description, String rerankModel) {
        transactions.executeWithoutResult(status -> {
            if (!bases.lock(id)) {
                throw notFound(id);
            }
            KnowledgeBase current = get(id);
            String newName = name == null ? current.name() : name.strip();
            String newDescription =
                    description == null ? current.description() : description.isBlank() ? null : description.strip();
            String newRerank =
                    rerankModel == null ? current.rerankModel() : rerankModel.isBlank() ? null : rerankModel.strip();
            bases.update(id, newName, newDescription, newRerank, clock.instant());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("name", newName);
            details.put("rerank_model", newRerank);
            audit.record("knowledge_base.updated", "knowledge_base", id, details);
        });
        return get(id);
    }

    /**
     * 删除知识库与它的全部文档、切片、写入作业与存储计量记录，提交后按前缀删除对象存储中的原文件（D81，ADR-0053）。正在进行的写入
     * 在结束事务中发现文档已删除，结果被丢弃。引用它的已发布智能体版本不修改，之后检索得到"知识库不存在"。
     *
     * @param id 知识库 ID
     * @throws NotFoundException 不存在或不属于当前项目时
     */
    public void delete(String id) {
        PlatformContext context = PlatformContexts.current();
        KnowledgeBase deleted = transactions.execute(status -> {
            if (!bases.lock(id)) {
                throw notFound(id);
            }
            KnowledgeBase current = get(id);
            bases.delete(id);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("name", current.name());
            details.put("document_count", current.documentCount());
            audit.record("knowledge_base.deleted", "knowledge_base", id, details);
            return current;
        });
        String prefix = KnowledgeBase.objectPrefix(context.tenantId(), context.projectId(), deleted.id());
        try {
            int removed = objects.deleteByPrefix(prefix);
            log.info("知识库已删除 knowledgeBaseId={} objects={}", id, removed);
        } catch (RuntimeException e) {
            // 行已经删除，残留的对象不再被引用
            log.warn("知识库的原文件删除失败，对象残留 prefix={}", prefix, e);
        }
    }

    /**
     * 读取当前项目的知识库。
     *
     * @param id 知识库 ID
     * @return 知识库
     * @throws NotFoundException 不存在或不属于当前项目时（{@code knowledge_base.not_found}）
     */
    public KnowledgeBase get(String id) {
        return bases.find(id).orElseThrow(() -> notFound(id));
    }

    /**
     * 分页列出当前项目的知识库。
     *
     * @param page 分页参数
     * @return 一页知识库
     */
    public PageResult<KnowledgeBase> list(PageRequest page) {
        return PageResult.of(bases.list(page), page, base -> Cursor.of(base.id()));
    }

    /**
     * 内部接口：按 ID 查找，不按租户过滤（调用方校验归属）。
     *
     * @param id 知识库 ID
     * @return 知识库
     * @throws NotFoundException 不存在时
     */
    public KnowledgeBase lookup(String id) {
        return lookup.find(id).orElseThrow(() -> notFound(id));
    }

    /**
     * 知识库不存在的异常。
     *
     * @param id 知识库 ID
     * @return 异常
     */
    public static NotFoundException notFound(String id) {
        return new NotFoundException(KnowledgeErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在：" + id);
    }
}
