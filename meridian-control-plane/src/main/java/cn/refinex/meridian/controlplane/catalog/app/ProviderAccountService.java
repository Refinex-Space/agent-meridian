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
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.catalog.domain.CatalogErrorCode;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderAccount;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderType;
import cn.refinex.meridian.controlplane.catalog.domain.RateLimit;
import cn.refinex.meridian.controlplane.catalog.infra.ProviderAccountRepository;
import cn.refinex.meridian.controlplane.secret.app.SecretService;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.controlplane.web.Endpoints;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 供应商账号的管理用例（ADR-0045、ADR-0046）：端点按地址策略校验（D52），凭据必须是本租户的租户级凭据；修改与删除提交后经
 * {@code config.invalidate} 通知 runtime；账号下还有模型时不能删除。
 */
@Service
public class ProviderAccountService {

    private final ProviderAccountRepository accounts;
    private final SecretService secrets;
    private final EndpointPolicy endpoints;
    private final AdminAudit audit;
    private final ConfigInvalidations invalidations;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param accounts 供应商账号仓储
     * @param secrets 凭据用例，校验凭据引用
     * @param endpoints 端点策略
     * @param audit 审计
     * @param invalidations 配置变更广播
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public ProviderAccountService(
            ProviderAccountRepository accounts,
            SecretService secrets,
            EndpointPolicy endpoints,
            AdminAudit audit,
            ConfigInvalidations invalidations,
            TransactionTemplate transactions,
            Clock clock) {
        this.accounts = accounts;
        this.secrets = secrets;
        this.endpoints = endpoints;
        this.audit = audit;
        this.invalidations = invalidations;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 创建供应商账号。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param name 名称
     * @param provider 类型
     * @param baseUrl 端点；{@code openai_compatible} 必填
     * @param secretId 凭据 ID；可为空
     * @param rateLimit 限流
     * @return 新账号
     */
    public ProviderAccount create(
            String actor,
            String tenantId,
            String name,
            ProviderType provider,
            String baseUrl,
            String secretId,
            RateLimit rateLimit) {
        validate(tenantId, provider, baseUrl, secretId);
        Instant now = clock.instant();
        ProviderAccount account = new ProviderAccount(
                Ids.newId(IdPrefix.PROVIDER_ACCOUNT),
                tenantId,
                name,
                provider,
                baseUrl,
                secretId,
                rateLimit,
                actor,
                now,
                now);
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    try {
                        accounts.insert(account);
                    } catch (DuplicateKeyException e) {
                        throw new ConflictException(CatalogErrorCode.NAME_TAKEN, "租户内已有名为 " + name + " 的供应商账号");
                    }
                    audit.record(
                            "provider_account.created",
                            actor,
                            tenantId,
                            null,
                            "provider_account",
                            account.id(),
                            details(account));
                    return account;
                }));
    }

    /**
     * 读取供应商账号。
     *
     * @param tenantId 租户 ID
     * @param id 账号 ID
     * @return 账号
     */
    public ProviderAccount get(String tenantId, String id) {
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> require(id, false));
    }

    /**
     * 列出供应商账号。
     *
     * @param tenantId 租户 ID
     * @param page 分页参数
     * @return 一页账号
     */
    public PageResult<ProviderAccount> list(String tenantId, PageRequest page) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId), () -> PageResult.of(accounts.list(page), page, a -> Cursor.of(a.id())));
    }

    /**
     * 修改：整体替换可改的字段。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param id 账号 ID
     * @param name 名称
     * @param baseUrl 端点
     * @param secretId 凭据 ID
     * @param rateLimit 限流
     * @return 修改后的账号
     */
    public ProviderAccount update(
            String actor,
            String tenantId,
            String id,
            String name,
            String baseUrl,
            String secretId,
            RateLimit rateLimit) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    ProviderAccount current = require(id, true);
                    validate(tenantId, current.provider(), baseUrl, secretId);
                    ProviderAccount updated = new ProviderAccount(
                            id,
                            tenantId,
                            name,
                            current.provider(),
                            baseUrl,
                            secretId,
                            rateLimit,
                            current.createdBy(),
                            current.createdAt(),
                            clock.instant());
                    try {
                        accounts.update(updated);
                    } catch (DuplicateKeyException e) {
                        throw new ConflictException(CatalogErrorCode.NAME_TAKEN, "租户内已有名为 " + name + " 的供应商账号");
                    }
                    audit.record(
                            "provider_account.updated",
                            actor,
                            tenantId,
                            null,
                            "provider_account",
                            id,
                            details(updated));
                    invalidations.resourceChanged(ConfigInvalidations.PROVIDER_ACCOUNT, tenantId, id);
                    return updated;
                }));
    }

    /**
     * 删除：账号下还有模型时以 {@code catalog.in_use} 拒绝并列出模型。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param id 账号 ID
     */
    public void delete(String actor, String tenantId, String id) {
        PlatformContexts.run(
                Scopes.tenant(tenantId),
                () -> transactions.executeWithoutResult(status -> {
                    ProviderAccount account = require(id, true);
                    List<ResourceReference> models = accounts.models(id);
                    if (!models.isEmpty()) {
                        throw new ReferencedException(
                                CatalogErrorCode.IN_USE, "供应商账号 " + account.name() + " 下还有模型，先删除这些模型", models);
                    }
                    accounts.delete(id);
                    audit.record(
                            "provider_account.deleted",
                            actor,
                            tenantId,
                            null,
                            "provider_account",
                            id,
                            details(account));
                    invalidations.resourceChanged(ConfigInvalidations.PROVIDER_ACCOUNT, tenantId, id);
                }));
    }

    /**
     * 读取账号，不存在时以 404 失败。必须在租户的 {@code PlatformContext} 中调用。
     *
     * @param id 账号 ID
     * @param forUpdate 是否加行锁
     * @return 账号
     */
    ProviderAccount require(String id, boolean forUpdate) {
        return accounts.find(id, forUpdate)
                .orElseThrow(
                        () -> new NotFoundException(CatalogErrorCode.PROVIDER_ACCOUNT_NOT_FOUND, "供应商账号不存在：" + id));
    }

    private void validate(String tenantId, ProviderType provider, String baseUrl, String secretId) {
        if (provider == ProviderType.OPENAI_COMPATIBLE && (baseUrl == null || baseUrl.isBlank())) {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "openai_compatible 类型的供应商账号必须给出 base_url",
                    List.of(new FieldViolation("base_url", "openai_compatible 必填")));
        }
        Endpoints.check(endpoints, baseUrl, "base_url");
        if (secretId != null) {
            secrets.requireUsable(tenantId, null, secretId, "secret_id");
        }
    }

    private static Map<String, Object> details(ProviderAccount account) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", account.name());
        details.put("provider", account.provider().value());
        details.put("base_url", account.baseUrl());
        details.put("secret_id", account.secretId());
        return details;
    }
}
