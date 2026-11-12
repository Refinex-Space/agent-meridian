package cn.refinex.meridian.controlplane.metering.app;

import cn.refinex.meridian.controlplane.budget.app.BudgetAccounting;
import cn.refinex.meridian.controlplane.budget.domain.BudgetCharge;
import cn.refinex.meridian.controlplane.metering.domain.CostCalculator;
import cn.refinex.meridian.controlplane.metering.domain.UsageMessages;
import cn.refinex.meridian.controlplane.metering.domain.UsageRecord;
import cn.refinex.meridian.controlplane.metering.infra.system.UsageLedgerRepository;
import cn.refinex.meridian.controlplane.metering.infra.system.UsageLedgerRepository.PricedVersion;
import cn.refinex.meridian.persistence.redis.RedisStreamOutboxPublisher;
import cn.refinex.meridian.persistence.redis.StreamConsumer;
import cn.refinex.meridian.persistence.redis.StreamMessage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 用量消费者的处理逻辑（架构设计「跨服务数据流」，ADR-0048 第 3、7 条）：解析 Stream {@code usage} 的一批消息，补上智能体与按价格表
 * 版本计算的成本，在一个事务中按 {@code (id, occurred_at)} 幂等入账；真正插入的模型 token 记录同时计入四级预算的账本汇总
 * （{@link BudgetAccounting}），重投不会重复计入。
 *
 * <p>消息所在月份的分区不存在时先创建（独立的语句，不在入账事务中）。价格表版本与智能体版本不可变，查到的结果缓存在内存中。
 */
@Service
public class UsageIngestion implements StreamConsumer.Handler {

    /** 用量的 Stream 键，即 outbox 主题。 */
    public static final String STREAM = "usage";

    /** 内存缓存的上限：超过后清空重来（价格表与智能体版本的数量远小于它）。 */
    private static final int CACHE_LIMIT = 10_000;

    private static final Logger log = LoggerFactory.getLogger(UsageIngestion.class);

    private final UsageLedgerRepository ledger;
    private final BudgetAccounting accounting;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Map<String, PricedVersion> prices = new ConcurrentHashMap<>();
    private final Map<String, String> agents = new ConcurrentHashMap<>();
    private final Set<LocalDate> partitions = ConcurrentHashMap.newKeySet();

    /**
     * 创建处理逻辑。
     *
     * @param ledger 账本仓储
     * @param accounting 预算的账本汇总
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public UsageIngestion(
            UsageLedgerRepository ledger, BudgetAccounting accounting, TransactionTemplate transactions, Clock clock) {
        this.ledger = ledger;
        this.accounting = accounting;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void handle(List<StreamMessage> messages) {
        List<UsageRecord> records = new ArrayList<>(messages.size());
        for (StreamMessage message : messages) {
            UsageRecord parsed = UsageMessages.parse(
                    message.field(RedisStreamOutboxPublisher.FIELD_ID),
                    message.field(RedisStreamOutboxPublisher.FIELD_PAYLOAD));
            records.add(enrich(parsed));
        }
        records.stream().map(r -> month(r.occurredAt())).distinct().forEach(this::ensurePartitions);
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            for (UsageRecord record : records) {
                if (ledger.insert(record, now) && record.billableTokens() > 0) {
                    accounting.charge(new BudgetCharge(
                            record.tenantId(),
                            record.projectId(),
                            record.apiKeyId(),
                            record.sessionId(),
                            record.occurredAt(),
                            record.billableTokens()));
                }
            }
        });
    }

    @Override
    public boolean deadLetter(StreamMessage message, RuntimeException error) {
        transactions.executeWithoutResult(status -> ledger.deadLetter(
                STREAM,
                message.recordId(),
                message.field(RedisStreamOutboxPublisher.FIELD_ID),
                message.field(RedisStreamOutboxPublisher.FIELD_PAYLOAD),
                String.valueOf(error.getMessage()),
                clock.instant()));
        return true;
    }

    /** 补上智能体与成本：模型 token 记录带了价格表版本时计价，币种取价格表的。 */
    private UsageRecord enrich(UsageRecord record) {
        String agentId = record.agentVersionId() == null ? null : agentOf(record.agentVersionId());
        if (!record.kind().tokens() || record.priceBookId() == null || record.priceBookVersion() == null) {
            return record.priced(agentId, null, null);
        }
        Optional<PricedVersion> version =
                priceVersion(record.tenantId(), record.priceBookId(), record.priceBookVersion());
        if (version.isEmpty()) {
            log.warn(
                    "用量记录的价格表版本不存在，按未计价入账 id={} priceBookId={} version={}",
                    record.id(),
                    record.priceBookId(),
                    record.priceBookVersion());
            return record.priced(agentId, null, null);
        }
        BigDecimal cost = CostCalculator.cost(record, version.get().prices());
        return record.priced(agentId, version.get().currency(), cost);
    }

    private Optional<PricedVersion> priceVersion(String tenantId, String priceBookId, int version) {
        String key = tenantId + "/" + priceBookId + "/" + version;
        PricedVersion cached = prices.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<PricedVersion> found = ledger.priceVersion(tenantId, priceBookId, version);
        found.ifPresent(value -> remember(prices, key, value));
        return found;
    }

    private String agentOf(String agentVersionId) {
        String cached = agents.get(agentVersionId);
        if (cached != null) {
            return cached;
        }
        Optional<String> found = ledger.agentOf(agentVersionId);
        found.ifPresent(value -> remember(agents, agentVersionId, value));
        return found.orElse(null);
    }

    private void ensurePartitions(LocalDate month) {
        if (partitions.contains(month)) {
            return;
        }
        ledger.ensurePartitions(month);
        partitions.add(month);
    }

    private static <V> void remember(Map<String, V> cache, String key, V value) {
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(key, value);
    }

    private static LocalDate month(Instant at) {
        return at.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    }
}
