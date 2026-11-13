package cn.refinex.meridian.controlplane.secret.app;

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
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.project.app.ProjectService;
import cn.refinex.meridian.controlplane.secret.domain.Secret;
import cn.refinex.meridian.controlplane.secret.domain.SecretCipher;
import cn.refinex.meridian.controlplane.secret.domain.SecretErrorCode;
import cn.refinex.meridian.controlplane.secret.infra.SecretReferenceRepository;
import cn.refinex.meridian.controlplane.secret.infra.SecretRepository;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 凭据的管理用例（架构设计「凭据」，ADR-0045）：写入、轮换、删除、查看元数据与引用方。没有读出明文的用例；明文只在
 * {@link SecretResolution} 中为 runtime 解密。审计记录与日志中只有凭据 ID、名称与版本。
 */
@Service
public class SecretService {

    private final SecretRepository secrets;
    private final SecretReferenceRepository references;
    private final DataKeys dataKeys;
    private final ProjectService projects;
    private final AdminAudit audit;
    private final ConfigInvalidations invalidations;
    private final TransactionTemplate transactions;
    private final SecureRandom random;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param secrets 凭据仓储
     * @param references 引用方查询
     * @param dataKeys 按租户的数据密钥
     * @param projects 项目用例，确认项目存在
     * @param audit 审计
     * @param invalidations 配置变更广播
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public SecretService(
            SecretRepository secrets,
            SecretReferenceRepository references,
            DataKeys dataKeys,
            ProjectService projects,
            AdminAudit audit,
            ConfigInvalidations invalidations,
            TransactionTemplate transactions,
            Clock clock) {
        this.secrets = secrets;
        this.references = references;
        this.dataKeys = dataKeys;
        this.projects = projects;
        this.audit = audit;
        this.invalidations = invalidations;
        this.transactions = transactions;
        this.random = new SecureRandom();
        this.clock = clock;
    }

    /**
     * 写入凭据。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param projectId 所属项目；为空表示租户级凭据
     * @param name 名称
     * @param value 凭据值
     * @return 元数据与引用方（新凭据没有引用方）
     */
    public SecretView create(String actor, String tenantId, String projectId, String name, String value) {
        if (projectId != null) {
            projects.require(tenantId, projectId);
        }
        // 数据密钥在业务事务之外取得（它有自己的事务），见 DataKeys
        byte[] dataKey = dataKeys.forTenant(tenantId);
        Instant now = clock.instant();
        Secret secret = new Secret(Ids.newId(IdPrefix.SECRET), tenantId, projectId, name, 1, actor, now, now, null);
        byte[] ciphertext = SecretCipher.encrypt(
                dataKey,
                value.getBytes(StandardCharsets.UTF_8),
                SecretCipher.secretAad(tenantId, secret.id(), 1),
                random);
        PlatformContexts.run(
                Scopes.tenant(tenantId),
                () -> transactions.executeWithoutResult(status -> {
                    try {
                        secrets.insert(secret, ciphertext);
                    } catch (DuplicateKeyException e) {
                        throw new ConflictException(SecretErrorCode.NAME_TAKEN, "同一范围内已有名为 " + name + " 的凭据");
                    }
                    audit.record("secret.created", actor, tenantId, projectId, "secret", secret.id(), details(secret));
                }));
        return new SecretView(secret, List.of());
    }

    /**
     * 轮换：写入新值，版本号加一。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param secretId 凭据 ID
     * @param value 新值
     * @return 轮换后的元数据与引用方
     */
    public SecretView rotate(String actor, String tenantId, String secretId, String value) {
        byte[] dataKey = dataKeys.forTenant(tenantId);
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    Secret current = require(secretId, true);
                    int version = current.version() + 1;
                    byte[] ciphertext = SecretCipher.encrypt(
                            dataKey,
                            value.getBytes(StandardCharsets.UTF_8),
                            SecretCipher.secretAad(tenantId, secretId, version),
                            random);
                    secrets.rotate(secretId, version, ciphertext, clock.instant());
                    Secret rotated = require(secretId, false);
                    audit.record(
                            "secret.rotated",
                            actor,
                            tenantId,
                            rotated.projectId(),
                            "secret",
                            secretId,
                            details(rotated));
                    invalidations.resourceChanged(ConfigInvalidations.SECRET, tenantId, secretId);
                    return view(rotated);
                }));
    }

    /**
     * 删除凭据。仍被引用时以 {@code secret.in_use} 拒绝并列出引用方。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param secretId 凭据 ID
     */
    public void delete(String actor, String tenantId, String secretId) {
        PlatformContexts.run(
                Scopes.tenant(tenantId),
                () -> transactions.executeWithoutResult(status -> {
                    Secret secret = require(secretId, true);
                    List<ResourceReference> referencedBy =
                            references.referencesOf(List.of(secretId)).getOrDefault(secretId, List.of());
                    if (!referencedBy.isEmpty()) {
                        throw new ReferencedException(
                                SecretErrorCode.IN_USE, "凭据 " + secret.name() + " 仍被引用，先改掉引用方再删除", referencedBy);
                    }
                    secrets.delete(secretId);
                    audit.record(
                            "secret.deleted", actor, tenantId, secret.projectId(), "secret", secretId, details(secret));
                    invalidations.resourceChanged(ConfigInvalidations.SECRET, tenantId, secretId);
                }));
    }

    /**
     * 查看元数据与引用方。
     *
     * @param tenantId 租户 ID
     * @param secretId 凭据 ID
     * @return 元数据与引用方
     */
    public SecretView get(String tenantId, String secretId) {
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> view(require(secretId, false)));
    }

    /**
     * 列出凭据。
     *
     * @param tenantId 租户 ID
     * @param page 分页参数
     * @return 一页元数据与引用方
     */
    public PageResult<SecretView> list(String tenantId, PageRequest page) {
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> {
            PageResult<Secret> result = PageResult.of(secrets.list(page), page, s -> Cursor.of(s.id()));
            Map<String, List<ResourceReference>> refs = references.referencesOf(
                    result.data().stream().map(Secret::id).toList());
            return new PageResult<>(
                    result.data().stream()
                            .map(s -> new SecretView(s, refs.getOrDefault(s.id(), List.of())))
                            .toList(),
                    result.hasMore(),
                    result.nextCursor());
        });
    }

    /**
     * 校验一个资源可以引用某个凭据（创建、修改供应商账号时）。
     *
     * @param tenantId 引用方的租户
     * @param projectId 引用方的项目；为空表示租户级的引用方，只能引用租户级凭据
     * @param secretId 凭据 ID
     * @param field 请求中的字段名，用于校验失败的说明
     * @throws ValidationException 凭据不存在或不能被该范围使用时
     */
    public void requireUsable(String tenantId, String projectId, String secretId, String field) {
        boolean usable = PlatformContexts.call(Scopes.tenant(tenantId), () -> secrets.find(secretId, false))
                .map(s -> s.usableBy(tenantId, projectId))
                .orElse(false);
        if (!usable) {
            String reason = projectId == null ? "必须是本租户的租户级凭据" : "必须是本租户或本项目的凭据";
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "凭据 " + secretId + " 不存在或不能被该资源使用",
                    List.of(new FieldViolation(field, reason)));
        }
    }

    /**
     * 是否为租户级凭据（不属于任何项目）；不存在时为 false。
     *
     * @param tenantId 租户 ID
     * @param secretId 凭据 ID
     * @return 租户级时为 true
     */
    public boolean tenantLevel(String tenantId, String secretId) {
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> secrets.find(secretId, false))
                .map(secret -> secret.projectId() == null)
                .orElse(false);
    }

    private SecretView view(Secret secret) {
        return new SecretView(
                secret, references.referencesOf(List.of(secret.id())).getOrDefault(secret.id(), List.of()));
    }

    private Secret require(String secretId, boolean forUpdate) {
        return secrets.find(secretId, forUpdate)
                .orElseThrow(() -> new NotFoundException(SecretErrorCode.NOT_FOUND, "凭据不存在：" + secretId));
    }

    private static Map<String, Object> details(Secret secret) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", secret.name());
        details.put("version", secret.version());
        return details;
    }

    /**
     * 凭据的元数据与引用方。
     *
     * @param secret 元数据
     * @param references 引用方
     */
    public record SecretView(Secret secret, List<ResourceReference> references) {}
}
