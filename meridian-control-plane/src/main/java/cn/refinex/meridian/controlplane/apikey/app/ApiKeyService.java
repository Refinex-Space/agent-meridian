package cn.refinex.meridian.controlplane.apikey.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.apikey.domain.ApiKey;
import cn.refinex.meridian.controlplane.apikey.domain.ApiKeyErrorCode;
import cn.refinex.meridian.controlplane.apikey.domain.ApiKeySecret;
import cn.refinex.meridian.controlplane.apikey.infra.ApiKeyRepository;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.config.ControlPlaneProperties;
import cn.refinex.meridian.controlplane.project.app.ProjectService;
import cn.refinex.meridian.controlplane.project.domain.ProjectDeleted;
import cn.refinex.meridian.controlplane.web.Scopes;
import cn.refinex.meridian.security.apikey.ApiKeyHashes;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * API Key 的管理用例（架构设计「认证」，ADR-0042）：生成（明文只返回一次，库中只有 SHA-256 与随机部分的前 8 位）、查看、改名、
 * 吊销（提交后经 {@code key.revoked} 广播）。项目删除时在同一事务中吊销其全部 Key。审计与日志只出现 Key ID 与显示前缀。
 */
@Service
public class ApiKeyService {

    private final ApiKeyRepository keys;
    private final ProjectService projects;
    private final ApiKeyRevocations revocations;
    private final AdminAudit audit;
    private final TransactionTemplate transactions;
    private final ControlPlaneProperties properties;
    private final SecureRandom random;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param keys API Key 仓储
     * @param projects 项目用例，确认项目存在
     * @param revocations 吊销广播
     * @param audit 审计
     * @param transactions 事务模板
     * @param properties control-plane 配置，取默认限流
     * @param clock 时钟
     */
    public ApiKeyService(
            ApiKeyRepository keys,
            ProjectService projects,
            ApiKeyRevocations revocations,
            AdminAudit audit,
            TransactionTemplate transactions,
            ControlPlaneProperties properties,
            Clock clock) {
        this.keys = keys;
        this.projects = projects;
        this.revocations = revocations;
        this.audit = audit;
        this.transactions = transactions;
        this.properties = properties;
        this.random = new SecureRandom();
        this.clock = clock;
    }

    /**
     * 创建 API Key。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param name 名称
     * @param scopes 作用域，至少一个
     * @param expiresAt 过期时间；可为空，表示不过期
     * @param rateLimitPerMinute 每分钟上限；可为空，取默认值
     * @return 元数据与一次性的明文
     * @throws ValidationException 过期时间不晚于当前时间时
     */
    public Created create(
            String actor,
            String tenantId,
            String projectId,
            String name,
            Set<ApiKeyScope> scopes,
            Instant expiresAt,
            Integer rateLimitPerMinute) {
        projects.require(tenantId, projectId);
        Instant now = clock.instant();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "expires_at 必须晚于当前时间",
                    List.of(new FieldViolation("expires_at", "必须晚于当前时间")));
        }
        ApiKeySecret secret = ApiKeySecret.generate(random);
        ApiKey key = new ApiKey(
                Ids.newId(IdPrefix.API_KEY),
                tenantId,
                projectId,
                name,
                secret.visiblePrefix(),
                scopes,
                rateLimitPerMinute == null ? properties.apiKeys().defaultRateLimitPerMinute() : rateLimitPerMinute,
                expiresAt,
                actor,
                now,
                null);
        PlatformContexts.run(
                Scopes.project(tenantId, projectId),
                () -> transactions.executeWithoutResult(status -> {
                    keys.insert(key, ApiKeyHashes.sha256(secret.plaintext()));
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("display_prefix", key.displayPrefix());
                    details.put(
                            "scopes",
                            key.scopes().stream()
                                    .map(ApiKeyScope::value)
                                    .sorted()
                                    .toList());
                    details.put("rate_limit_per_minute", key.rateLimitPerMinute());
                    details.put("expires_at", expiresAt == null ? null : expiresAt.toString());
                    audit.record("api_key.created", actor, tenantId, projectId, "api_key", key.id(), details);
                }));
        return new Created(key, secret);
    }

    /**
     * 读取 API Key。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param keyId API Key ID
     * @return 元数据
     */
    public ApiKey get(String tenantId, String projectId, String keyId) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(Scopes.project(tenantId, projectId), () -> require(keyId, false));
    }

    /**
     * 列出 API Key，包括已吊销的。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param page 分页参数
     * @return 一页元数据
     */
    public PageResult<ApiKey> list(String tenantId, String projectId, PageRequest page) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> PageResult.of(keys.list(page), page, k -> Cursor.of(k.id())));
    }

    /**
     * 改名。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param keyId API Key ID
     * @param name 新名称
     * @return 修改后的元数据
     */
    public ApiKey rename(String actor, String tenantId, String projectId, String keyId, String name) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    ApiKey key = require(keyId, true);
                    keys.rename(keyId, name);
                    audit.record(
                            "api_key.updated",
                            actor,
                            tenantId,
                            projectId,
                            "api_key",
                            keyId,
                            Map.of("display_prefix", key.displayPrefix(), "name", name));
                    return require(keyId, false);
                }));
    }

    /**
     * 吊销。已吊销的 Key 再次吊销不做任何事，返回原结果。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param keyId API Key ID
     * @return 吊销后的元数据
     */
    public ApiKey revoke(String actor, String tenantId, String projectId, String keyId) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    ApiKey key = require(keyId, true);
                    if (keys.revoke(keyId, clock.instant())) {
                        revoked(actor, key);
                    }
                    return require(keyId, false);
                }));
    }

    /**
     * 项目删除时吊销它的全部 Key（与删除项目同一事务）。
     *
     * @param event 项目删除事件
     */
    @EventListener
    public void onProjectDeleted(ProjectDeleted event) {
        PlatformContexts.run(
                Scopes.project(event.tenantId(), event.projectId()),
                () -> keys.revokeAll(clock.instant()).forEach(key -> revoked(event.actor(), key)));
    }

    private void revoked(String actor, ApiKey key) {
        audit.record(
                "api_key.revoked",
                actor,
                key.tenantId(),
                key.projectId(),
                "api_key",
                key.id(),
                Map.of("display_prefix", key.displayPrefix()));
        revocations.revoked(key.id());
    }

    private ApiKey require(String keyId, boolean forUpdate) {
        return keys.find(keyId, forUpdate)
                .orElseThrow(() -> new NotFoundException(ApiKeyErrorCode.NOT_FOUND, "API Key 不存在：" + keyId));
    }

    /**
     * 新创建的 Key。
     *
     * @param key 元数据
     * @param secret 明文，只在创建响应中出现一次
     */
    public record Created(ApiKey key, ApiKeySecret secret) {}
}
