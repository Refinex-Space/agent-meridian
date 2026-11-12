package cn.refinex.meridian.controlplane.tenant.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.config.ControlPlaneProperties;
import cn.refinex.meridian.controlplane.member.app.MemberService;
import cn.refinex.meridian.controlplane.secret.app.SecretService;
import cn.refinex.meridian.controlplane.tenant.domain.Tenant;
import cn.refinex.meridian.controlplane.tenant.domain.TenantDeleted;
import cn.refinex.meridian.controlplane.tenant.domain.TenantErrorCode;
import cn.refinex.meridian.controlplane.tenant.domain.TenantSettings;
import cn.refinex.meridian.controlplane.tenant.infra.TenantRepository;
import cn.refinex.meridian.controlplane.tenant.infra.TenantSettingsRepository;
import cn.refinex.meridian.controlplane.tenant.infra.system.TenantDirectoryRepository;
import cn.refinex.meridian.controlplane.web.Endpoints;
import cn.refinex.meridian.controlplane.web.Scopes;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 租户的用例：创建（同时加入首个所有者）、查看、改名、配额、设置、删除（架构设计「租户层级」，ADR-0042）。修改并发配额后经
 * {@code config.invalidate} 通知 runtime；删除是软删除，在同一事务中发布 {@link TenantDeleted}，项目与 API Key 随之处理。
 */
@Service
public class TenantService {

    private final TenantRepository tenants;
    private final TenantSettingsRepository settings;
    private final TenantDirectoryRepository directory;
    private final MemberService members;
    private final ConfigInvalidations invalidations;
    private final AdminAudit audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final ControlPlaneProperties properties;
    private final EndpointPolicy endpoints;
    private final SecretService secrets;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param tenants 租户仓储
     * @param settings 租户设置仓储
     * @param directory 跨租户的租户目录
     * @param members 成员用例，加入首个所有者
     * @param invalidations 配置变更广播
     * @param audit 审计
     * @param events 进程内事件（同一事务中同步处理）
     * @param transactions 事务模板
     * @param properties control-plane 配置，取默认配额
     * @param endpoints 端点策略，校验联网搜索后端的地址（ADR-0045，D52）
     * @param secrets 凭据用例，校验联网搜索后端的凭据是本租户的租户级凭据（D61）
     * @param clock 时钟
     */
    public TenantService(
            TenantRepository tenants,
            TenantSettingsRepository settings,
            TenantDirectoryRepository directory,
            MemberService members,
            ConfigInvalidations invalidations,
            AdminAudit audit,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            ControlPlaneProperties properties,
            EndpointPolicy endpoints,
            SecretService secrets,
            Clock clock) {
        this.tenants = tenants;
        this.settings = settings;
        this.directory = directory;
        this.members = members;
        this.invalidations = invalidations;
        this.audit = audit;
        this.events = events;
        this.transactions = transactions;
        this.properties = properties;
        this.endpoints = endpoints;
        this.secrets = secrets;
        this.clock = clock;
    }

    /**
     * 创建租户，并把 {@code owner} 加为成员、授予租户所有者。
     *
     * @param actor 操作者（平台管理员）的主体标识
     * @param name 名称
     * @param owner 首个所有者
     * @param maxConcurrentTurns 并发配额；可为空，取默认值
     * @param eventRetentionDays 事件保留天数；可为空，取默认值
     * @return 新租户
     */
    public Tenant create(
            String actor, String name, NewOwner owner, Integer maxConcurrentTurns, Integer eventRetentionDays) {
        String tenantId = Ids.newId(IdPrefix.TENANT);
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    Instant now = clock.instant();
                    Tenant tenant = new Tenant(
                            tenantId,
                            name,
                            maxConcurrentTurns == null
                                    ? properties.tenants().defaultMaxConcurrentTurns()
                                    : maxConcurrentTurns,
                            eventRetentionDays == null
                                    ? properties.tenants().defaultEventRetentionDays()
                                    : eventRetentionDays,
                            now,
                            now);
                    tenants.insert(tenant);
                    audit.record("tenant.created", actor, tenantId, null, "tenant", tenantId, quotaDetails(tenant));
                    members.addFirstOwner(actor, tenantId, owner.subject(), owner.email(), owner.displayName());
                    return tenant;
                }));
    }

    /**
     * 列出调用方能看到的租户：平台管理员看到全部，其他用户只看到自己是成员的。
     *
     * @param access 调用方的授权上下文
     * @param page 分页参数
     * @return 一页租户
     */
    public PageResult<Tenant> list(AccessContext access, PageRequest page) {
        var rows = access.permits(Permission.TENANT_LIST_ALL, null)
                ? directory.listAll(page)
                : directory.listForSubject(access.user().subject(), page);
        return PageResult.of(rows, page, t -> Cursor.of(t.id()));
    }

    /**
     * 读取租户。
     *
     * @param tenantId 租户 ID
     * @return 租户
     * @throws NotFoundException 不存在或已删除时
     */
    public Tenant get(String tenantId) {
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> require(false));
    }

    /**
     * 租户是否存在且未删除。
     *
     * @param tenantId 租户 ID
     * @return 存在时为 true
     */
    public boolean exists(String tenantId) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId), () -> tenants.find(false).isPresent());
    }

    /**
     * 改名。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param name 新名称
     * @return 修改后的租户
     */
    public Tenant rename(String actor, String tenantId, String name) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    require(true);
                    tenants.updateName(name, clock.instant());
                    audit.record("tenant.updated", actor, tenantId, null, "tenant", tenantId, Map.of("name", name));
                    return require(false);
                }));
    }

    /**
     * 修改配额；并发配额在提交后经 {@code config.invalidate} 下发到 runtime。
     *
     * @param actor 操作者（平台管理员）的主体标识
     * @param tenantId 租户 ID
     * @param maxConcurrentTurns 并发配额
     * @param eventRetentionDays 事件保留天数
     * @return 修改后的租户
     */
    public Tenant updateQuotas(String actor, String tenantId, int maxConcurrentTurns, int eventRetentionDays) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    Tenant before = require(true);
                    tenants.updateQuotas(maxConcurrentTurns, eventRetentionDays, clock.instant());
                    Tenant after = require(false);
                    audit.record(
                            "tenant.quota_updated", actor, tenantId, null, "tenant", tenantId, quotaDetails(after));
                    if (before.maxConcurrentTurns() != after.maxConcurrentTurns()) {
                        invalidations.tenantQuotaChanged(tenantId);
                    }
                    return after;
                }));
    }

    /**
     * 读取租户设置；从未设置时为默认值。
     *
     * @param tenantId 租户 ID
     * @return 设置
     */
    public TenantSettings settings(String tenantId) {
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> {
            require(false);
            return settings.find().orElseGet(TenantSettings::defaults);
        });
    }

    /**
     * 整体替换租户设置：联网搜索后端的端点按地址策略校验、凭据必须是本租户的租户级凭据（D61）；提交后经 {@code config.invalidate}
     * （{@code tenant_settings}）通知 runtime，新的轮次使用新设置（ADR-0047）。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param systemPrompt 租户层系统提示词
     * @param webSearch 联网搜索后端；可为空
     * @return 新设置
     */
    public TenantSettings updateSettings(
            String actor, String tenantId, String systemPrompt, TenantSettings.WebSearch webSearch) {
        if (webSearch != null) {
            Endpoints.check(endpoints, webSearch.baseUrl(), "web_search.base_url");
            if (webSearch.secretId() != null) {
                secrets.requireUsable(tenantId, null, webSearch.secretId(), "web_search.secret_id");
            }
        }
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    require(true);
                    TenantSettings updated = new TenantSettings(systemPrompt, webSearch, clock.instant());
                    settings.upsert(updated);
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("web_search_backend", webSearch == null ? null : webSearch.backend());
                    audit.record("tenant_settings.updated", actor, tenantId, null, "tenant", tenantId, details);
                    invalidations.resourceChanged(ConfigInvalidations.TENANT_SETTINGS, tenantId, tenantId);
                    return updated;
                }));
    }

    /**
     * 删除租户：标记删除，并在同一事务中删除其全部项目、吊销全部 API Key。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     */
    public void delete(String actor, String tenantId) {
        PlatformContexts.run(
                Scopes.tenant(tenantId),
                () -> transactions.executeWithoutResult(status -> {
                    require(true);
                    tenants.markDeleted(clock.instant());
                    audit.record("tenant.deleted", actor, tenantId, null, "tenant", tenantId, Map.of());
                    events.publishEvent(new TenantDeleted(tenantId, actor));
                }));
    }

    private Tenant require(boolean forUpdate) {
        String tenantId = PlatformContexts.current().tenantId();
        return tenants.find(forUpdate)
                .orElseThrow(() -> new NotFoundException(TenantErrorCode.NOT_FOUND, "租户不存在：" + tenantId));
    }

    private static Map<String, Object> quotaDetails(Tenant tenant) {
        return Map.of(
                "name", tenant.name(),
                "max_concurrent_turns", tenant.maxConcurrentTurns(),
                "event_retention_days", tenant.eventRetentionDays());
    }

    /**
     * 新租户的首个所有者。
     *
     * @param subject 主体标识
     * @param email 邮箱；可为空
     * @param displayName 显示名称；可为空
     */
    public record NewOwner(String subject, String email, String displayName) {}
}
