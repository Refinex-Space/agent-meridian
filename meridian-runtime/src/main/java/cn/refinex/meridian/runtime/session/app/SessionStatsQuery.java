package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.runtime.session.domain.SessionStats;
import cn.refinex.meridian.runtime.session.domain.SessionStats.AgentStats;
import cn.refinex.meridian.runtime.session.domain.StatsWindow;
import cn.refinex.meridian.runtime.session.infra.SessionStatsRepository;
import cn.refinex.meridian.runtime.session.infra.system.TurnStatsRollupRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * 会话运行概况（{@code GET /v1/session-stats}，ADR-0053）：计数在请求时读取，失败率读每分钟汇总一次的小时表。在当前项目内执行。
 * 线程安全：无状态。
 */
@Service
public class SessionStatsQuery {

    /** 最近错误至多几个。 */
    static final int RECENT_ERRORS = 10;

    private final SessionStatsRepository repository;
    private final TurnStatsRollupRepository rollups;
    private final Clock clock;

    /**
     * 创建查询。
     *
     * @param repository 项目级统计仓储
     * @param rollups 汇总仓储，读水位
     * @param clock 时钟
     */
    public SessionStatsQuery(SessionStatsRepository repository, TurnStatsRollupRepository rollups, Clock clock) {
        this.repository = repository;
        this.rollups = rollups;
        this.clock = clock;
    }

    /**
     * 当前项目的会话运行概况。
     *
     * @param window 时间窗
     * @param agentId 只统计该智能体；可为空
     * @return 概况
     */
    public SessionStats stats(StatsWindow window, String agentId) {
        Instant now = clock.instant();
        Instant start = window.start(now);
        long[] live = repository.liveSessions(agentId);
        Map<String, long[]> active = repository.activeSessionsByAgent(agentId);
        Map<String, long[]> turns = repository.turnsByAgent(start, agentId);
        TreeSet<String> agentIds = new TreeSet<>(active.keySet());
        agentIds.addAll(turns.keySet());
        List<AgentStats> agents = new ArrayList<>();
        for (String id : agentIds) {
            long[] sessions = active.getOrDefault(id, new long[2]);
            long[] ended = turns.getOrDefault(id, new long[2]);
            agents.add(new AgentStats(id, sessions[0], sessions[1], ended[0], ended[1]));
        }
        return new SessionStats(
                window,
                start,
                now,
                rollups.watermark().orElse(null),
                live[0],
                live[1],
                repository.pendingActions(agentId),
                agents,
                repository.recentErrors(start, agentId, RECENT_ERRORS));
    }
}
