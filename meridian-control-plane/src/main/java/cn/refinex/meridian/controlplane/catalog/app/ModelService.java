package cn.refinex.meridian.controlplane.catalog.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.error.ReferencedException;
import cn.refinex.meridian.common.error.ResourceReference;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.agent.domain.SpecReference;
import cn.refinex.meridian.controlplane.agent.infra.AgentVersionRefRepository;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.catalog.domain.CatalogErrorCode;
import cn.refinex.meridian.controlplane.catalog.domain.CatalogModel;
import cn.refinex.meridian.controlplane.catalog.domain.ModelCapabilities;
import cn.refinex.meridian.controlplane.catalog.domain.ModelKind;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderAccount;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderType;
import cn.refinex.meridian.controlplane.catalog.domain.RateLimit;
import cn.refinex.meridian.controlplane.catalog.infra.ModelRepository;
import cn.refinex.meridian.controlplane.catalog.infra.PriceBookRepository;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.controlplane.web.Endpoints;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 模型的管理用例（ADR-0045 第 7 条）：Embedding 与 rerank 只能挂在 OpenAI 兼容账号下；备用模型必须同租户、同种类、不是自己；价格表
 * 必须属于本租户；模型自己的端点按地址策略校验。修改与删除提交后经 {@code config.invalidate} 通知 runtime；被其他模型作为备用时不能
 * 删除。
 */
@Service
public class ModelService {

    private final ModelRepository models;
    private final PriceBookRepository priceBooks;
    private final ProviderAccountService accounts;
    private final EndpointPolicy endpoints;
    private final AdminAudit audit;
    private final ConfigInvalidations invalidations;
    private final AgentVersionRefRepository agentVersions;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param models 模型仓储
     * @param priceBooks 价格表仓储，校验价格表引用
     * @param accounts 供应商账号用例，校验所属账号
     * @param endpoints 端点策略
     * @param audit 审计
     * @param invalidations 配置变更广播
     * @param agentVersions 已发布智能体版本的引用，删除前检查
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public ModelService(
            ModelRepository models,
            PriceBookRepository priceBooks,
            ProviderAccountService accounts,
            EndpointPolicy endpoints,
            AdminAudit audit,
            ConfigInvalidations invalidations,
            AgentVersionRefRepository agentVersions,
            TransactionTemplate transactions,
            Clock clock) {
        this.models = models;
        this.priceBooks = priceBooks;
        this.accounts = accounts;
        this.endpoints = endpoints;
        this.audit = audit;
        this.invalidations = invalidations;
        this.agentVersions = agentVersions;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 创建模型。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param draft 模型的内容（ID、时间由本方法给出）
     * @return 新模型
     */
    public CatalogModel create(String actor, String tenantId, ModelDraft draft) {
        Endpoints.check(endpoints, draft.baseUrl(), "base_url");
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    ProviderAccount account = accounts.require(draft.providerAccountId(), false);
                    if (draft.kind() != ModelKind.CHAT && account.provider() != ProviderType.OPENAI_COMPATIBLE) {
                        throw invalid("kind", draft.kind().value() + " 模型只能挂在 openai_compatible 供应商账号下");
                    }
                    String id = Ids.newId(IdPrefix.MODEL);
                    Instant now = clock.instant();
                    CatalogModel model = draft.toModel(id, tenantId, actor, now, now);
                    validateReferences(model);
                    try {
                        models.insert(model);
                    } catch (DuplicateKeyException e) {
                        throw new ConflictException(CatalogErrorCode.NAME_TAKEN, "租户内已有名为 " + draft.name() + " 的模型");
                    }
                    audit.record("model.created", actor, tenantId, null, "model", id, details(model));
                    return model;
                }));
    }

    /**
     * 读取模型。
     *
     * @param tenantId 租户 ID
     * @param id 模型 ID
     * @return 模型
     */
    public CatalogModel get(String tenantId, String id) {
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> require(id, false));
    }

    /**
     * 列出模型。
     *
     * @param tenantId 租户 ID
     * @param page 分页参数
     * @return 一页模型
     */
    public PageResult<CatalogModel> list(String tenantId, PageRequest page) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId), () -> PageResult.of(models.list(page), page, m -> Cursor.of(m.id())));
    }

    /**
     * 修改：整体替换可改的字段，供应商账号与种类不变。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param id 模型 ID
     * @param draft 新内容；其中的供应商账号与种类被忽略
     * @return 修改后的模型
     */
    public CatalogModel update(String actor, String tenantId, String id, ModelDraft draft) {
        Endpoints.check(endpoints, draft.baseUrl(), "base_url");
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    CatalogModel current = require(id, true);
                    CatalogModel updated = new ModelDraft(
                                    current.providerAccountId(),
                                    draft.name(),
                                    current.kind(),
                                    draft.upstreamModel(),
                                    draft.contextWindow(),
                                    draft.maxOutputTokens(),
                                    draft.capabilities(),
                                    draft.fallbackModelId(),
                                    draft.priceBookId(),
                                    draft.baseUrl(),
                                    draft.maxBatchSize(),
                                    draft.rateLimit())
                            .toModel(id, tenantId, current.createdBy(), current.createdAt(), clock.instant());
                    validateReferences(updated);
                    try {
                        models.update(updated);
                    } catch (DuplicateKeyException e) {
                        throw new ConflictException(CatalogErrorCode.NAME_TAKEN, "租户内已有名为 " + draft.name() + " 的模型");
                    }
                    audit.record("model.updated", actor, tenantId, null, "model", id, details(updated));
                    invalidations.resourceChanged(ConfigInvalidations.MODEL, tenantId, id);
                    return updated;
                }));
    }

    /**
     * 删除：被其他模型作为备用、或被未删除智能体的已发布版本引用时（ADR-0047），以 {@code catalog.in_use} 拒绝并列出它们。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param id 模型 ID
     */
    public void delete(String actor, String tenantId, String id) {
        PlatformContexts.run(
                Scopes.tenant(tenantId),
                () -> transactions.executeWithoutResult(status -> {
                    CatalogModel model = require(id, true);
                    List<ResourceReference> referencedBy = new ArrayList<>(models.fallbackReferences(id));
                    referencedBy.addAll(agentVersions.referencing(SpecReference.Type.MODEL, id));
                    if (!referencedBy.isEmpty()) {
                        throw new ReferencedException(
                                CatalogErrorCode.IN_USE, "模型 " + model.name() + " 仍是其他模型的备用模型", referencedBy);
                    }
                    models.delete(id);
                    audit.record("model.deleted", actor, tenantId, null, "model", id, details(model));
                    invalidations.resourceChanged(ConfigInvalidations.MODEL, tenantId, id);
                }));
    }

    private CatalogModel require(String id, boolean forUpdate) {
        return models.find(id, forUpdate)
                .orElseThrow(() -> new NotFoundException(CatalogErrorCode.MODEL_NOT_FOUND, "模型不存在：" + id));
    }

    /** 备用模型与价格表必须属于本租户；备用模型同种类、不是自己。 */
    private void validateReferences(CatalogModel model) {
        if (model.fallbackModelId() != null) {
            CatalogModel fallback = models.find(model.fallbackModelId(), false)
                    .orElseThrow(() -> invalid("fallback_model_id", "备用模型不存在：" + model.fallbackModelId()));
            if (fallback.kind() != model.kind()) {
                throw invalid(
                        "fallback_model_id", "备用模型必须与本模型同种类（" + model.kind().value() + "）");
            }
        }
        if (model.priceBookId() != null
                && priceBooks.find(model.priceBookId(), false).isEmpty()) {
            throw invalid("price_book_id", "价格表不存在：" + model.priceBookId());
        }
    }

    private static ValidationException invalid(String field, String reason) {
        return new ValidationException(
                CommonErrorCode.VALIDATION_FAILED, reason, List.of(new FieldViolation(field, reason)));
    }

    private static Map<String, Object> details(CatalogModel model) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", model.name());
        details.put("provider_account_id", model.providerAccountId());
        details.put("kind", model.kind().value());
        details.put("upstream_model", model.upstreamModel());
        details.put("fallback_model_id", model.fallbackModelId());
        details.put("price_book_id", model.priceBookId());
        return details;
    }

    /**
     * 管理接口给出的模型内容。
     *
     * @param providerAccountId 供应商账号
     * @param name 名称
     * @param kind 种类
     * @param upstreamModel 供应商侧的模型名
     * @param contextWindow 上下文窗口
     * @param maxOutputTokens 默认最大输出
     * @param capabilities 能力
     * @param fallbackModelId 备用模型；可为空
     * @param priceBookId 价格表；可为空
     * @param baseUrl 端点；可为空
     * @param maxBatchSize Embedding 批量上限；可为空
     * @param rateLimit 限流
     */
    public record ModelDraft(
            String providerAccountId,
            String name,
            ModelKind kind,
            String upstreamModel,
            int contextWindow,
            int maxOutputTokens,
            ModelCapabilities capabilities,
            String fallbackModelId,
            String priceBookId,
            String baseUrl,
            Integer maxBatchSize,
            RateLimit rateLimit) {

        CatalogModel toModel(String id, String tenantId, String createdBy, Instant createdAt, Instant updatedAt) {
            if (id.equals(fallbackModelId)) {
                throw invalid("fallback_model_id", "备用模型不能是自己");
            }
            return new CatalogModel(
                    id,
                    tenantId,
                    providerAccountId,
                    name,
                    kind,
                    upstreamModel,
                    contextWindow,
                    maxOutputTokens,
                    capabilities,
                    fallbackModelId,
                    priceBookId,
                    baseUrl,
                    maxBatchSize,
                    rateLimit,
                    createdBy,
                    createdAt,
                    updatedAt);
        }
    }
}
