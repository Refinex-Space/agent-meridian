package cn.refinex.meridian.controlplane.webhook.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.config.ControlPlaneProperties;
import cn.refinex.meridian.controlplane.project.app.ProjectDirectory;
import cn.refinex.meridian.controlplane.project.app.ProjectService;
import cn.refinex.meridian.controlplane.project.domain.ProjectErrorCode;
import cn.refinex.meridian.controlplane.secret.app.DataKeys;
import cn.refinex.meridian.controlplane.secret.domain.SecretCipher;
import cn.refinex.meridian.controlplane.secret.domain.SecretErrorCode;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.controlplane.web.Endpoints;
import cn.refinex.meridian.controlplane.web.Scopes;
import cn.refinex.meridian.controlplane.webhook.domain.Webhook;
import cn.refinex.meridian.controlplane.webhook.domain.WebhookErrorCode;
import cn.refinex.meridian.controlplane.webhook.infra.WebhookRepository;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Webhook 的用例（架构设计「Webhook」，ADR-0049）：增删改查、签名密钥的生成与加密保存，以及给 runtime 的订阅读取（带密钥明文）。
 *
 * <p>签名密钥是 {@code whsec_} 加 32 字节随机数的 base64url，用凭据库同一把租户数据密钥加密（附加认证数据
 * {@link SecretCipher#webhookAad}），明文只出现在创建的返回值与内部接口的响应中，不进审计与日志。变更在提交后发布
 * {@code config.invalidate}（{@link ConfigInvalidations#WEBHOOK}），runtime 淘汰该租户缓存的订阅。
 *
 * <p>方法会阻塞；线程安全：无状态。
 */
@Service
public class WebhookService {

    /** 签名密钥的前缀。 */
    static final String SECRET_PREFIX = "whsec_";

    /** 签名密钥的随机字节数。 */
    static final int SECRET_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookRepository webhooks;
    private final ProjectService projects;
    private final ProjectDirectory directory;
    private final DataKeys dataKeys;
    private final EndpointPolicy endpoints;
    private final AdminAudit audit;
    private final ConfigInvalidations invalidations;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param webhooks Webhook 仓储
     * @param projects 项目用例，确认项目存在
     * @param directory 按项目 ID 找租户（内部接口）
     * @param dataKeys 租户数据密钥
     * @param properties control-plane 配置，取 Webhook 的地址策略
     * @param audit 审计
     * @param invalidations 配置变更广播
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public WebhookService(
            WebhookRepository webhooks,
            ProjectService projects,
            ProjectDirectory directory,
            DataKeys dataKeys,
            ControlPlaneProperties properties,
            AdminAudit audit,
            ConfigInvalidations invalidations,
            TransactionTemplate transactions,
            Clock clock) {
        this.webhooks = webhooks;
        this.projects = projects;
        this.directory = directory;
        this.dataKeys = dataKeys;
        this.endpoints = properties.webhooks().policy();
        this.audit = audit;
        this.invalidations = invalidations;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 创建 Webhook 并生成签名密钥。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param draft 内容
     * @return 新的 Webhook 与签名密钥明文
     * @throws ValidationException 地址或事件不合法时
     */
    public Created create(String actor, String tenantId, String projectId, Draft draft) {
        projects.require(tenantId, projectId);
        List<String> events = validate(draft);
        String secret = newSecret();
        String id = Ids.newId(IdPrefix.WEBHOOK);
        // 数据密钥在业务事务之外取得（它有自己的事务），见 DataKeys
        byte[] ciphertext = SecretCipher.encrypt(
                dataKeys.forTenant(tenantId),
                secret.getBytes(StandardCharsets.UTF_8),
                SecretCipher.webhookAad(tenantId, id),
                random);
        Webhook webhook = PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    Instant now = clock.instant();
                    Webhook created = new Webhook(
                            id,
                            tenantId,
                            projectId,
                            draft.url(),
                            events,
                            draft.description(),
                            draft.enabled(),
                            actor,
                            now,
                            now);
                    webhooks.insert(created, ciphertext);
                    audit.record("webhook.created", actor, tenantId, projectId, "webhook", id, details(created));
                    invalidations.resourceChanged(ConfigInvalidations.WEBHOOK, tenantId, id);
                    return created;
                }));
        return new Created(webhook, secret);
    }

    /**
     * 整体替换地址、事件、说明与是否启用；签名密钥不变。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id Webhook ID
     * @param draft 新内容
     * @return 修改后的 Webhook
     * @throws NotFoundException Webhook 不存在时（{@code webhook.not_found}）
     */
    public Webhook update(String actor, String tenantId, String projectId, String id, Draft draft) {
        projects.require(tenantId, projectId);
        List<String> events = validate(draft);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    Webhook current = require(id, true);
                    Webhook updated = new Webhook(
                            id,
                            tenantId,
                            projectId,
                            draft.url(),
                            events,
                            draft.description(),
                            draft.enabled(),
                            current.createdBy(),
                            current.createdAt(),
                            clock.instant());
                    webhooks.update(updated);
                    audit.record("webhook.updated", actor, tenantId, projectId, "webhook", id, details(updated));
                    invalidations.resourceChanged(ConfigInvalidations.WEBHOOK, tenantId, id);
                    return updated;
                }));
    }

    /**
     * 读取。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id Webhook ID
     * @return Webhook
     * @throws NotFoundException Webhook 不存在时
     */
    public Webhook get(String tenantId, String projectId, String id) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(Scopes.project(tenantId, projectId), () -> require(id, false));
    }

    /**
     * 列出。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param page 分页参数
     * @return 一页 Webhook
     */
    public PageResult<Webhook> list(String tenantId, String projectId, PageRequest page) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> PageResult.of(webhooks.list(page), page, w -> Cursor.of(w.id())));
    }

    /**
     * 删除。已有的投递记录留在 runtime，之后的投递以失败结束。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id Webhook ID
     * @throws NotFoundException Webhook 不存在时
     */
    public void delete(String actor, String tenantId, String projectId, String id) {
        projects.require(tenantId, projectId);
        PlatformContexts.run(
                Scopes.project(tenantId, projectId),
                () -> transactions.executeWithoutResult(status -> {
                    Webhook webhook = require(id, true);
                    webhooks.delete(id);
                    audit.record("webhook.deleted", actor, tenantId, projectId, "webhook", id, details(webhook));
                    invalidations.resourceChanged(ConfigInvalidations.WEBHOOK, tenantId, id);
                }));
    }

    /**
     * 给 runtime 的订阅：项目的全部 Webhook 与签名密钥明文（内部接口 {@code GET /internal/v1/projects/{id}/webhooks}）。
     *
     * @param projectId 项目 ID
     * @return Webhook 与密钥，按 ID 升序
     * @throws NotFoundException 项目不存在或已删除时（{@code project.not_found}）
     * @throws MeridianException 密钥无法解密时（{@code secret.encryption_unavailable}）
     */
    public List<Subscription> subscriptions(String projectId) {
        String tenantId = directory
                .tenantOf(projectId)
                .orElseThrow(() -> new NotFoundException(ProjectErrorCode.NOT_FOUND, "项目不存在：" + projectId));
        List<WebhookRepository.WithSecret> stored = PlatformContexts.call(
                Scopes.project(tenantId, projectId), () -> transactions.execute(status -> webhooks.listWithSecrets()));
        if (stored.isEmpty()) {
            return List.of();
        }
        byte[] dataKey = dataKeys.forTenant(tenantId);
        return stored.stream()
                .map(row -> new Subscription(row.webhook(), decrypt(dataKey, tenantId, row)))
                .toList();
    }

    private String decrypt(byte[] dataKey, String tenantId, WebhookRepository.WithSecret row) {
        try {
            return new String(
                    SecretCipher.decrypt(
                            dataKey,
                            row.secretCiphertext(),
                            SecretCipher.webhookAad(tenantId, row.webhook().id())),
                    StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            log.error(
                    "Webhook 的签名密钥无法解密 tenantId={} webhookId={}",
                    tenantId,
                    row.webhook().id());
            throw new MeridianException(SecretErrorCode.ENCRYPTION_UNAVAILABLE, "Webhook 的签名密钥无法解密，请联系管理员", e);
        }
    }

    private Webhook require(String id, boolean forUpdate) {
        return webhooks.find(id, forUpdate)
                .orElseThrow(() -> new NotFoundException(WebhookErrorCode.NOT_FOUND, "Webhook 不存在：" + id));
    }

    private List<String> validate(Draft draft) {
        Endpoints.check(endpoints, draft.url(), "url");
        try {
            return Webhook.normalize(draft.events());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED,
                    e.getMessage(),
                    List.of(new FieldViolation("events", e.getMessage())));
        }
    }

    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, Object> details(Webhook webhook) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("url", webhook.url());
        details.put("events", webhook.events());
        details.put("enabled", webhook.enabled());
        return details;
    }

    /**
     * Webhook 的内容（创建或整体替换）。
     *
     * @param url 接收方地址
     * @param events 订阅的事件类型
     * @param description 说明；可为空
     * @param enabled 是否启用
     */
    public record Draft(String url, List<String> events, String description, boolean enabled) {}

    /**
     * 创建结果。
     *
     * @param webhook 新的 Webhook
     * @param signingSecret 签名密钥明文，只在这里出现一次；{@link #toString()} 不输出它
     */
    public record Created(Webhook webhook, String signingSecret) {

        @Override
        public String toString() {
            return "Created[webhook=" + webhook + ", signingSecret=***]";
        }
    }

    /**
     * 给 runtime 的一条订阅。
     *
     * @param webhook Webhook
     * @param signingSecret 签名密钥明文；{@link #toString()} 不输出它
     */
    public record Subscription(Webhook webhook, String signingSecret) {

        @Override
        public String toString() {
            return "Subscription[webhook=" + webhook + ", signingSecret=***]";
        }
    }
}
