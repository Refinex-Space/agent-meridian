package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.persistence.jdbc.PeriodicJob;
import cn.refinex.meridian.runtime.session.app.TurnStatsRollup;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话统计的后台汇总（ADR-0053）：每分钟把结束的轮次按智能体累加进小时表。{@code meridian.runtime.stats.enabled=false} 时不运行
 * （测试共用一个库，手动驱动 {@link TurnStatsRollup#rollUpTo}）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(value = "meridian.runtime.stats.enabled", matchIfMissing = true)
public class StatsConfiguration {

    /**
     * 失败率的小时汇总。
     *
     * @param rollup 作业
     * @return 后台任务
     */
    @Bean
    public PeriodicJob turnStatsRollupJob(TurnStatsRollup rollup) {
        return new PeriodicJob(
                "turn-stats-rollup", TurnStatsRollup.INTERVAL, rollup::runOnce, TurnWorkerLifecycle.LEASE_JOBS_PHASE);
    }
}
