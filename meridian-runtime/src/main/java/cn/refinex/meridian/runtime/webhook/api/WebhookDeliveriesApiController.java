package cn.refinex.meridian.runtime.webhook.api;

import cn.refinex.meridian.api.model.WebhookDeliveryPage;
import cn.refinex.meridian.api.model.WebhookDeliveryStatus;
import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.runtime.openapi.WebhooksApi;
import cn.refinex.meridian.runtime.web.ApiTimes;
import cn.refinex.meridian.runtime.web.IdempotentRequests;
import cn.refinex.meridian.runtime.webhook.app.WebhookDeliveries;
import cn.refinex.meridian.runtime.webhook.domain.DeliveryFilter;
import cn.refinex.meridian.runtime.webhook.domain.DeliveryStatus;
import cn.refinex.meridian.runtime.webhook.domain.WebhookDelivery;
import cn.refinex.meridian.security.apikey.ApiKeyAuthorization;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Webhook 投递记录的公开 API（{@code /v1/webhook-deliveries}，ADR-0049）：查询要 {@code sessions:read}，重投要 {@code sessions:write}。 */
@RestController
public class WebhookDeliveriesApiController implements WebhooksApi {

    private final WebhookDeliveries deliveries;
    private final IdempotentRequests idempotent;

    /**
     * 创建处理器。
     *
     * @param deliveries 投递记录用例
     * @param idempotent POST 的幂等执行
     */
    public WebhookDeliveriesApiController(WebhookDeliveries deliveries, IdempotentRequests idempotent) {
        this.deliveries = deliveries;
        this.idempotent = idempotent;
    }

    @Override
    public Mono<ResponseEntity<WebhookDeliveryPage>> listWebhookDeliveries(
            Integer limit,
            String after,
            String webhookId,
            String sessionId,
            WebhookDeliveryStatus status,
            OffsetDateTime createdAfter,
            OffsetDateTime createdBefore,
            ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        PageRequest page = PageRequest.of(limit, after);
        DeliveryFilter filter = new DeliveryFilter(
                webhookId,
                sessionId,
                status == null ? null : DeliveryStatus.of(status.getValue()),
                createdAfter == null ? null : createdAfter.toInstant(),
                createdBefore == null ? null : createdBefore.toInstant());
        return Blocking.call(() -> toPage(deliveries.list(page, filter))).map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<cn.refinex.meridian.api.model.WebhookDelivery>> getWebhookDelivery(
            String deliveryId, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        return Blocking.call(() -> toDto(deliveries.get(deliveryId))).map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<cn.refinex.meridian.api.model.WebhookDelivery>> redeliverWebhookDelivery(
            String deliveryId, String idempotencyKey, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_WRITE);
        return idempotent.execute(
                exchange,
                idempotencyKey,
                null,
                HttpStatus.ACCEPTED,
                cn.refinex.meridian.api.model.WebhookDelivery.class,
                () -> toDto(deliveries.redeliver(deliveryId)));
    }

    private static WebhookDeliveryPage toPage(PageResult<WebhookDelivery> page) {
        return new WebhookDeliveryPage(
                page.data().stream().map(WebhookDeliveriesApiController::toDto).toList(),
                page.hasMore(),
                page.nextCursor());
    }

    private static cn.refinex.meridian.api.model.WebhookDelivery toDto(WebhookDelivery delivery) {
        return new cn.refinex.meridian.api.model.WebhookDelivery()
                .id(delivery.id())
                .webhookId(delivery.webhookId())
                .sessionId(delivery.sessionId())
                .eventId(delivery.eventId())
                .eventType(delivery.eventType())
                .status(WebhookDeliveryStatus.fromValue(delivery.status().value()))
                .attempts(delivery.attempts())
                .nextAttemptAt(ApiTimes.utc(delivery.nextAttemptAt()))
                .lastAttemptAt(ApiTimes.utc(delivery.lastAttemptAt()))
                .lastResponseStatus(delivery.lastResponseStatus())
                .lastError(delivery.lastError())
                .redeliveryOf(delivery.redeliveryOf())
                .createdAt(ApiTimes.utc(delivery.createdAt()))
                .completedAt(ApiTimes.utc(delivery.completedAt()));
    }
}
