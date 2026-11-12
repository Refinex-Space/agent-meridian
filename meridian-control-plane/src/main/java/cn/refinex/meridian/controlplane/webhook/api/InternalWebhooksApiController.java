package cn.refinex.meridian.controlplane.webhook.api;

import cn.refinex.meridian.api.internal.model.InternalWebhook;
import cn.refinex.meridian.api.internal.model.InternalWebhookList;
import cn.refinex.meridian.controlplane.openapi.internal.InternalWebhooksApi;
import cn.refinex.meridian.controlplane.webhook.app.WebhookService;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialFilter;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 给 runtime 的 Webhook 订阅（{@code GET /internal/v1/projects/{project_id}/webhooks}，ADR-0049）：带签名密钥明文，不缓存。 */
@RestController
public class InternalWebhooksApiController implements InternalWebhooksApi {

    private final WebhookService webhooks;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param webhooks Webhook 用例
     * @param request 当前请求（Spring 注入的代理）
     */
    public InternalWebhooksApiController(WebhookService webhooks, HttpServletRequest request) {
        this.webhooks = webhooks;
        this.request = request;
    }

    @Override
    public ResponseEntity<InternalWebhookList> listInternalProjectWebhooks(String projectId) {
        ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME);
        InternalWebhookList list = new InternalWebhookList(webhooks.subscriptions(projectId).stream()
                .map(subscription -> new InternalWebhook()
                        .id(subscription.webhook().id())
                        .tenantId(subscription.webhook().tenantId())
                        .projectId(subscription.webhook().projectId())
                        .url(subscription.webhook().url())
                        .events(subscription.webhook().events().stream()
                                .map(InternalWebhook.EventsEnum::fromValue)
                                .toList())
                        .enabled(subscription.webhook().enabled())
                        .signingSecret(subscription.signingSecret()))
                .toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(list);
    }
}
