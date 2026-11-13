package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.runtime.session.domain.StatsWindow;
import cn.refinex.meridian.runtime.session.infra.system.TurnStatsRollupRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 按智能体的失败率汇总作业（ADR-0053）：每分钟把水位之后结束的轮次按项目、智能体、小时累加进 {@code rt.agent_turn_stats}，
 * 推进水位，清理 8 天前的小时行。不在轮次结束的事务里累加：同一智能体的轮次结束会争用同一行，拖慢主路径。
 *
 * <p>只汇总到"当前时间减 {@link #LAG}"：轮次的 {@code finished_at} 在提交之前取自时钟，滞后量覆盖结束事务的提交延迟，晚于水位
 * 提交的轮次不会被漏掉。多个节点同时执行时经水位的行锁串行，同一区间只累加一次。线程安全：无状态。
 */
@Service
public class TurnStatsRollup {

    /** 汇总的间隔。 */
    public static final Duration INTERVAL = Duration.ofMinutes(1);

    /** 汇总到当前时间之前多久，覆盖结束轮次的事务从取时间到提交的延迟。 */
    public static final Duration LAG = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(TurnStatsRollup.class);

    private final TurnStatsRollupRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建作业。
     *
     * @param repository 汇总仓储
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public TurnStatsRollup(TurnStatsRollupRepository repository, TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** 定期任务的入口：汇总到当前时间减 {@link #LAG}。 */
    public void runOnce() {
        rollUpTo(clock.instant().minus(LAG));
    }

    /**
     * 汇总到给定时间（含）。第一次执行时从保留期的起点开始，已有的轮次一并计入。测试直接调用它。
     *
     * @param to 汇总到
     * @return 累加的（项目、智能体、小时）组数
     */
    public int rollUpTo(Instant to) {
        Instant now = clock.instant();
        Integer groups = transactions.execute(status -> {
            Instant watermark = repository.lockWatermark(now.minus(StatsWindow.RETENTION), now);
            if (!to.isAfter(watermark)) {
                return 0;
            }
            int accumulated = repository.accumulate(watermark, to, now);
            repository.advance(to, now);
            repository.deleteBefore(now.minus(StatsWindow.RETENTION));
            return accumulated;
        });
        int result = groups == null ? 0 : groups;
        if (result > 0) {
            log.debug("轮次汇总已推进 to={} groups={}", to, result);
        }
        return result;
    }
}
