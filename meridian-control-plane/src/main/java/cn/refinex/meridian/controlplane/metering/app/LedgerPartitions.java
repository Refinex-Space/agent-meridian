package cn.refinex.meridian.controlplane.metering.app;

import cn.refinex.meridian.controlplane.metering.infra.system.UsageLedgerRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账本分区的维护（架构设计「数据保留」，ADR-0048）：确保 {@code usage_records} 与 {@code audit_logs} 本月与下个月的分区存在（提前创建下个
 * 月的），删除超过 3 年的分区。启动时与之后每隔一段时间执行；多实例同时执行时由函数内的咨询锁串行。
 */
@Service
public class LedgerPartitions {

    private static final Logger log = LoggerFactory.getLogger(LedgerPartitions.class);

    private final UsageLedgerRepository ledger;
    private final Clock clock;

    /**
     * 创建维护任务。
     *
     * @param ledger 账本仓储
     * @param clock 时钟，决定"本月"
     */
    public LedgerPartitions(UsageLedgerRepository ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * 执行一次维护。
     *
     * @return 新建与删除的分区数
     */
    public Result maintain() {
        LocalDate thisMonth =
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1);
        int created = ledger.ensurePartitions(thisMonth) + ledger.ensurePartitions(thisMonth.plusMonths(1));
        int dropped = ledger.dropExpiredPartitions();
        if (created > 0 || dropped > 0) {
            log.info("账本分区维护 created={} dropped={} month={}", created, dropped, thisMonth);
        }
        return new Result(created, dropped);
    }

    /**
     * 一次维护的结果。
     *
     * @param created 新建的分区数
     * @param dropped 删除的分区数
     */
    public record Result(int created, int dropped) {}
}
