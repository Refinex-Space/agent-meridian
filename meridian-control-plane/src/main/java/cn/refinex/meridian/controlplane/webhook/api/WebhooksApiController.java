package cn.refinex.meridian.controlplane.webhook.api;

import cn.refinex.meridian.api.admin.model.CreatedWebhook;
import cn.refinex.meridian.api.admin.model.WebhookEventType;
import cn.refinex.meridian.api.admin.model.WebhookPage;
import cn.refinex.meridian.api.admin.model.WebhookRequest;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.openapi.admin.WebhooksApi;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.controlplane.webhook.app.WebhookService;
import cn.refinex.meridian.controlplane.webhook.domain.Webhook;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook 的管理接口（{@code /admin/v1/tenants/{t}/projects/{p}/webhooks}，ADR-0049）。权限同 API Key：两者都是业务系统的接入配置。
 * 签名密钥只在创建响应（与它的幂等重放）中出现。
 */
@RestController
public class WebhooksApiController implements WebhooksApi {

    private final WebhookService webhooks;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param webhooks Webhook 用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public WebhooksApiController(WebhookService webhooks, AdminRequests requests, HttpServletRequest request) {
        this.webhooks = webhooks;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.API_KEY_WRITE)
    public ResponseEntity<CreatedWebhook> createWebhook(
            String tenantId, String projectId, WebhookRequest body, String idempotencyKey) {
        WebhookService.Draft draft = draft(body);
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                CreatedWebhook.class,
                () -> created(webhooks.create(actor(), tenantId, projectId, draft)));
    }

    @Override
    @RequiresPermission(Permission.API_KEY_READ)
    public ResponseEntity<WebhookPage> listWebhooks(String tenantId, String projectId, Integer limit, String after) {
        var page = webhooks.list(tenantId, projectId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new WebhookPage(
                page.data().stream().map(WebhooksApiController::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.API_KEY_READ)
    public ResponseEntity<cn.refinex.meridian.api.admin.model.Webhook> getWebhook(
            String tenantId, String projectId, String webhookId) {
        return ResponseEntity.ok(toDto(webhooks.get(tenantId, projectId, webhookId)));
    }

    @Override
    @RequiresPermission(Permission.API_KEY_WRITE)
    public ResponseEntity<cn.refinex.meridian.api.admin.model.Webhook> updateWebhook(
            String tenantId, String projectId, String webhookId, WebhookRequest body, String idempotencyKey) {
        WebhookService.Draft draft = draft(body);
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                cn.refinex.meridian.api.admin.model.Webhook.class,
                () -> toDto(webhooks.update(actor(), tenantId, projectId, webhookId, draft)));
    }

    @Override
    @RequiresPermission(Permission.API_KEY_WRITE)
    public ResponseEntity<Void> deleteWebhook(
            String tenantId, String projectId, String webhookId, String idempotencyKey) {
        return requests.delete(
                request, tenantId, idempotencyKey, () -> webhooks.delete(actor(), tenantId, projectId, webhookId));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }

    private static WebhookService.Draft draft(WebhookRequest body) {
        return new WebhookService.Draft(
                body.getUrl(),
                body.getEvents().stream().map(WebhookEventType::getValue).toList(),
                body.getDescription(),
                body.getEnabled() == null || body.getEnabled());
    }

    private static cn.refinex.meridian.api.admin.model.Webhook toDto(Webhook webhook) {
        return new cn.refinex.meridian.api.admin.model.Webhook()
                .id(webhook.id())
                .tenantId(webhook.tenantId())
                .projectId(webhook.projectId())
                .url(webhook.url())
                .events(webhook.events().stream()
                        .map(WebhookEventType::fromValue)
                        .toList())
                .description(webhook.description())
                .enabled(webhook.enabled())
                .createdBy(webhook.createdBy())
                .createdAt(Times.utc(webhook.createdAt()))
                .updatedAt(Times.utc(webhook.updatedAt()));
    }

    private static CreatedWebhook created(WebhookService.Created created) {
        Webhook webhook = created.webhook();
        return new CreatedWebhook()
                .id(webhook.id())
                .tenantId(webhook.tenantId())
                .projectId(webhook.projectId())
                .url(webhook.url())
                .events(webhook.events().stream()
                        .map(WebhookEventType::fromValue)
                        .toList())
                .description(webhook.description())
                .enabled(webhook.enabled())
                .createdBy(webhook.createdBy())
                .createdAt(Times.utc(webhook.createdAt()))
                .updatedAt(Times.utc(webhook.updatedAt()))
                .signingSecret(created.signingSecret());
    }
}
