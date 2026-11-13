package cn.refinex.meridian.runtime.webhook.app;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.runtime.webhook.domain.RetrySchedule;
import cn.refinex.meridian.runtime.webhook.domain.WebhookSignature;
import cn.refinex.meridian.runtime.webhook.domain.WebhookSubscription;
import cn.refinex.meridian.runtime.webhook.infra.system.WebhookQueueRepository;
import cn.refinex.meridian.runtime.webhook.infra.system.WebhookQueueRepository.DueDelivery;
import cn.refinex.meridian.runtime.webhook.infra.system.WebhookQueueRepository.OutboxEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Webhook 投递器（架构设计「Webhook」，ADR-0049）。每个节点上的定期任务调用 {@link #runOnce()}，与轮次执行完全解耦：
 * <ol>
 *   <li><b>扇出</b>：认领 outbox（{@code rt.webhook_events}）中的事件，按项目读取订阅，为每个启用且订阅了该类型的 Webhook 插入一条
 *       投递记录，删除 outbox 行。读不到订阅时保留该行，下次再试。
 *   <li><b>投递</b>：认领到期的待投递记录，在事务外签名并发出（每次按当前配置取地址与密钥），在新事务中记录结果：成功、按
 *       {@link RetrySchedule} 安排下一次、或最终失败。结果写库之前进程崩溃时认领过期，由任一节点再投一次（至少一次）。
 *   <li><b>保留期</b>：删除创建早于保留期、已结束的记录。
 * </ol>
 *
 * <p>指标 {@code meridian.webhook.deliveries}（标签 {@code outcome}：{@code succeeded}、{@code retry}、{@code failed}）。方法会阻塞；
 * 线程安全：多个节点、同一节点的多次调用都可以并发，认领保证同一行只被一处处理。
 */
@Service
public class WebhookDispatcher {

    /** 投递结果的指标名，导出为 {@code meridian_webhook_deliveries_total}。 */
    public static final String DELIVERY_METRIC = "meridian.webhook.deliveries";

    /** Webhook 已删除或停用时记录的原因。 */
    static final String UNAVAILABLE = "webhook_unavailable: the webhook was deleted or disabled";

    /** 认领时已过 24 小时截止时间的记录（例如订阅一直读不到）不再发送，以这个原因结束（R2 审查 C-08）。 */
    static final String DEADLINE_EXCEEDED = "deadline_exceeded: the delivery deadline passed before it could be sent";

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookQueueRepository queue;
    private final WebhookSubscriptionPort subscriptions;
    private final WebhookSender sender;
    private final WebhookProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Counter succeeded;
    private final Counter retried;
    private final Counter failed;

    /**
     * 创建投递器。
     *
     * @param queue 跨租户的 outbox 与投递记录
     * @param subscriptions 项目的订阅
     * @param sender HTTP 发送
     * @param properties 配置
     * @param transactions 事务模板
     * @param meters 指标注册表
     * @param clock 时钟
     */
    public WebhookDispatcher(
            WebhookQueueRepository queue,
            WebhookSubscriptionPort subscriptions,
            WebhookSender sender,
            WebhookProperties properties,
            TransactionTemplate transactions,
            MeterRegistry meters,
            Clock clock) {
        this.queue = queue;
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
        this.succeeded = counter(meters, "succeeded");
        this.retried = counter(meters, "retry");
        this.failed = counter(meters, "failed");
    }

    /**
     * 执行一轮：扇出、投递到期的记录、清理过了保留期的记录。单个事件或记录失败只记日志。
     *
     * @return 本轮处理（扇出或投递）的条数
     */
    public int runOnce() {
        int fannedOut = fanOut();
        int delivered = deliverDue();
        queue.purgeFinishedBefore(clock.instant().minus(properties.deliveryRetention()), properties.batchSize());
        return fannedOut + delivered;
    }

    /**
     * 会话是否还有未扇出的事件或待投递的记录。会话清理据此等待删除通知投递完（ADR-0049）。
     *
     * @param sessionId 会话 ID
     * @return 有时为 true
     */
    public boolean hasUnfinished(String sessionId) {
        return Boolean.TRUE.equals(transactions.execute(status -> queue.hasUnfinished(sessionId)));
    }

    /**
     * 扇出一批 outbox 事件。
     *
     * @return 扇出（删除）的事件数
     */
    int fanOut() {
        Integer count = transactions.execute(status -> {
            Instant now = clock.instant();
            Map<String, List<WebhookSubscription>> byProject = new HashMap<>();
            int done = 0;
            for (OutboxEvent event : queue.claimEvents(properties.batchSize())) {
                List<WebhookSubscription> projectWebhooks;
                try {
                    projectWebhooks = byProject.computeIfAbsent(
                            event.projectId(), project -> subscriptions.forProject(event.tenantId(), project));
                } catch (RuntimeException e) {
                    log.warn("读不到项目的 Webhook 订阅，稍后重试 projectId={}", event.projectId(), e);
                    continue;
                }
                for (WebhookSubscription webhook : projectWebhooks) {
                    if (webhook.wants(event.eventType())) {
                        queue.insertDelivery(
                                Ids.newId(IdPrefix.WEBHOOK_DELIVERY),
                                event,
                                webhook.id(),
                                null,
                                now,
                                now.plus(RetrySchedule.WINDOW));
                    }
                }
                queue.deleteEvent(event.eventId());
                done++;
            }
            return done;
        });
        return count == null ? 0 : count;
    }

    /**
     * 认领并投递一批到期的记录，至多 {@code parallelism} 个请求同时进行。
     *
     * @return 认领的记录数
     */
    int deliverDue() {
        Instant now = clock.instant();
        List<DueDelivery> due = transactions.execute(
                status -> queue.claimDue(now, now.plus(properties.claimDuration()), properties.batchSize()));
        if (due == null || due.isEmpty()) {
            return 0;
        }
        Semaphore permits = new Semaphore(properties.parallelism());
        List<Future<?>> running = new ArrayList<>(due.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (DueDelivery delivery : due) {
                permits.acquireUninterruptibly();
                running.add(executor.submit(() -> {
                    try {
                        deliver(delivery);
                    } catch (RuntimeException e) {
                        log.warn("Webhook 投递出错，认领过期后重试 deliveryId={}", delivery.id(), e);
                    } finally {
                        permits.release();
                    }
                }));
            }
        }
        return running.size();
    }

    /** 投递一条认领到的记录并记录结果。 */
    private void deliver(DueDelivery delivery) {
        if (!clock.instant().isBefore(delivery.deadline())) {
            transactions.executeWithoutResult(
                    status -> queue.record(delivery, false, clock.instant(), null, DEADLINE_EXCEEDED, null, false));
            failed.increment();
            log.warn("Webhook 投递已过截止时间，不再发送 deliveryId={} deadline={}", delivery.id(), delivery.deadline());
            return;
        }
        Optional<WebhookSubscription> webhook =
                subscriptions.forProject(delivery.tenantId(), delivery.projectId()).stream()
                        .filter(candidate -> candidate.id().equals(delivery.webhookId()))
                        .filter(WebhookSubscription::enabled)
                        .findFirst();
        if (webhook.isEmpty()) {
            transactions.executeWithoutResult(
                    status -> queue.record(delivery, false, clock.instant(), null, UNAVAILABLE, null, false));
            failed.increment();
            log.info("Webhook 已删除或停用，投递以失败结束 deliveryId={} webhookId={}", delivery.id(), delivery.webhookId());
            return;
        }
        long timestamp = clock.instant().getEpochSecond();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(
                WebhookSignature.HEADER,
                WebhookSignature.header(webhook.get().signingSecret(), timestamp, delivery.payload()));
        headers.put("Meridian-Webhook-Id", delivery.webhookId());
        headers.put("Meridian-Delivery-Id", delivery.id());
        headers.put("Meridian-Event-Type", delivery.eventType());
        WebhookSender.Result result = sender.send(webhook.get().url(), headers, delivery.payload());

        Instant now = clock.instant();
        int attempts = delivery.attempts() + 1;
        Instant next = result.succeeded()
                ? null
                : RetrySchedule.next(attempts, now, delivery.deadline()).orElse(null);
        boolean recorded = Boolean.TRUE.equals(transactions.execute(status ->
                queue.record(delivery, true, now, result.status(), result.error(), next, result.succeeded())));
        if (!recorded) {
            log.warn("Webhook 投递的认领已过期，结果未记录 deliveryId={}", delivery.id());
            return;
        }
        if (result.succeeded()) {
            succeeded.increment();
        } else if (next != null) {
            retried.increment();
            log.info(
                    "Webhook 投递失败，稍后重试 deliveryId={} attempts={} status={} error={} next={}",
                    delivery.id(),
                    attempts,
                    result.status(),
                    result.error(),
                    next);
        } else {
            failed.increment();
            log.warn(
                    "Webhook 投递最终失败 deliveryId={} attempts={} status={} error={}",
                    delivery.id(),
                    attempts,
                    result.status(),
                    result.error());
        }
    }

    private static Counter counter(MeterRegistry meters, String outcome) {
        return Counter.builder(DELIVERY_METRIC)
                .description("Webhook 投递的结果")
                .tag("outcome", outcome)
                .register(meters);
    }
}
