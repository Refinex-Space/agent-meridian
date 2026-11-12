package cn.refinex.meridian.runtime.webhook.app;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.runtime.webhook.domain.DeliveryFilter;
import cn.refinex.meridian.runtime.webhook.domain.RetrySchedule;
import cn.refinex.meridian.runtime.webhook.domain.WebhookDelivery;
import cn.refinex.meridian.runtime.webhook.domain.WebhookErrorCode;
import cn.refinex.meridian.runtime.webhook.infra.WebhookDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 投递记录的查询与手动重投（公开 API {@code /v1/webhook-deliveries}，ADR-0049）。在当前项目内执行：仓储从 {@code PlatformContext} 取租户
 * 与项目，其他项目的记录与不存在相同。方法会阻塞；线程安全：无状态。
 */
@Service
public class WebhookDeliveries {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveries.class);

    private final WebhookDeliveryRepository deliveries;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param deliveries 项目级的投递记录仓储
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public WebhookDeliveries(WebhookDeliveryRepository deliveries, TransactionTemplate transactions, Clock clock) {
        this.deliveries = deliveries;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 列出本项目的投递记录，新的在前。
     *
     * @param page 分页参数
     * @param filter 过滤条件
     * @return 一页记录
     */
    public PageResult<WebhookDelivery> list(PageRequest page, DeliveryFilter filter) {
        return PageResult.of(deliveries.list(page, filter), page, delivery -> Cursor.of(delivery.id()));
    }

    /**
     * 读取一条。
     *
     * @param id 投递记录 ID
     * @return 记录
     * @throws NotFoundException 不存在时（{@code webhook_delivery.not_found}）
     */
    public WebhookDelivery get(String id) {
        return deliveries.find(id).orElseThrow(() -> notFound(id));
    }

    /**
     * 手动重投：以原记录的 Webhook 与事件生成一条新的待投递记录，立即到期，同样按退避重试。
     *
     * @param id 原记录 ID
     * @return 新记录
     * @throws NotFoundException 原记录不存在时
     */
    public WebhookDelivery redeliver(String id) {
        String newId = Ids.newId(IdPrefix.WEBHOOK_DELIVERY);
        boolean copied = Boolean.TRUE.equals(transactions.execute(status -> {
            Instant now = clock.instant();
            return deliveries.copyForRedelivery(id, newId, now, now.plus(RetrySchedule.WINDOW));
        }));
        if (!copied) {
            throw notFound(id);
        }
        log.info("Webhook 手动重投 deliveryId={} redeliveryOf={}", newId, id);
        return get(newId);
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException(WebhookErrorCode.DELIVERY_NOT_FOUND, "投递记录不存在：" + id);
    }
}
