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
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 知识库的用例（ADR-0051）。公开 API 的调用在 {@code ApiKeyFilter} 绑定的 {@code PlatformContext}（租户与项目）中执行。
 */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository bases;
    private final KnowledgeBaseLookupRepository lookup;
    private final KnowledgeAudit audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param bases 项目级仓储
     * @param lookup 跨租户的按 ID 查找
     * @param audit 审计
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public KnowledgeBaseService(
            KnowledgeBaseRepository bases,
            KnowledgeBaseLookupRepository lookup,
            KnowledgeAudit audit,
            TransactionTemplate transactions,
            Clock clock) {
        this.bases = bases;
        this.lookup = lookup;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 创建知识库，写审计 {@code knowledge_base.created}。Embedding 模型是否存在在写入第一份文档时由 runtime 校验。
     *
     * @param name 名称
     * @param description 说明；可为空
     * @param embeddingModel Embedding 模型 ID
     * @return 新建的知识库
     */
    public KnowledgeBase create(String name, String description, String embeddingModel) {
        PlatformContext context = PlatformContexts.current();
        Instant now = clock.instant();
        KnowledgeBase base = new KnowledgeBase(
                Ids.newId(IdPrefix.KNOWLEDGE_BASE),
                context.tenantId(),
                context.projectId(),
                name.strip(),
                description == null || description.isBlank() ? null : description.strip(),
                embeddingModel,
                0,
                context.actor(),
                now,
                now);
        transactions.executeWithoutResult(status -> {
            bases.insert(base);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("name", base.name());
            details.put("embedding_model", base.embeddingModel());
            audit.record("knowledge_base.created", "knowledge_base", base.id(), details);
        });
        return base;
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
