package cn.refinex.meridian.runtime.session.infra.system;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 按智能体的轮次小时汇总（{@code rt.agent_turn_stats}）与它的水位（{@code rt.stats_watermarks}），ADR-0053。方法在调用方的事务中执行。
 */
@Repository
@CrossTenant(reason = "后台作业按水位汇总全部租户结束的轮次；结果按租户与项目分行，查询经项目级仓储")
public class TurnStatsRollupRepository extends SystemRepository {

    /** 水位的名字。 */
    public static final String WATERMARK = "agent_turn_stats";

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public TurnStatsRollupRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 锁住水位行并读出水位；行不存在时先以给定的初始值插入。多个节点的作业经这把行锁串行。
     *
     * @param initial 第一次汇总的起点
     * @param now 当前时间
     * @return 水位
     */
    public Instant lockWatermark(Instant initial, Instant now) {
        jdbc().sql("""
                        INSERT INTO rt.stats_watermarks (name, watermark, updated_at)
                        VALUES (:name, :initial, :now)
                        ON CONFLICT (name) DO NOTHING
                        """)
                .param("name", WATERMARK)
                .param("initial", JdbcValues.timestamptz(initial))
                .param("now", JdbcValues.timestamptz(now))
                .update();
        return jdbc().sql("SELECT watermark FROM rt.stats_watermarks WHERE name = :name FOR UPDATE")
                .param("name", WATERMARK)
                .query((rs, row) -> JdbcValues.instant(rs, "watermark"))
                .single();
    }

    /**
     * 读出水位，不加锁。
     *
     * @return 水位；还没有汇总过时为空
     */
    public Optional<Instant> watermark() {
        return jdbc().sql("SELECT watermark FROM rt.stats_watermarks WHERE name = :name")
                .param("name", WATERMARK)
                .query((rs, row) -> JdbcValues.instant(rs, "watermark"))
                .optional();
    }

    /**
     * 把结束时间在 {@code (from, to]} 内的轮次（{@code completed}、{@code interrupted}、{@code failed}）按项目、智能体、UTC 小时累加进
     * 汇总表，走 {@code turns_finished_idx}。没有记录智能体的会话（T19b 之前创建的）不计。
     *
     * @param from 水位（不含）
     * @param to 汇总到（含）
     * @param now 当前时间
     * @return 累加的（项目、智能体、小时）组数
     */
    public int accumulate(Instant from, Instant to, Instant now) {
        return jdbc().sql("""
                        INSERT INTO rt.agent_turn_stats (tenant_id, project_id, agent_id, hour_start, turns, failed_turns,
                                                         updated_at)
                        SELECT t.tenant_id, t.project_id, s.agent_id, date_trunc('hour', t.finished_at, 'UTC'),
                               count(*), count(*) FILTER (WHERE t.status = 'failed'), :now
                          FROM rt.turns t
                          JOIN rt.sessions s ON s.id = t.session_id
                         WHERE t.status IN ('completed', 'interrupted', 'failed')
                           AND t.finished_at > :from AND t.finished_at <= :to AND s.agent_id IS NOT NULL
                         GROUP BY t.tenant_id, t.project_id, s.agent_id, date_trunc('hour', t.finished_at, 'UTC')
                        ON CONFLICT (tenant_id, project_id, agent_id, hour_start) DO UPDATE
                           SET turns = rt.agent_turn_stats.turns + EXCLUDED.turns,
                               failed_turns = rt.agent_turn_stats.failed_turns + EXCLUDED.failed_turns,
                               updated_at = EXCLUDED.updated_at
                        """)
                .param("from", JdbcValues.timestamptz(from))
                .param("to", JdbcValues.timestamptz(to))
                .param("now", JdbcValues.timestamptz(now))
                .update();
    }

    /**
     * 推进水位。
     *
     * @param watermark 新水位
     * @param now 当前时间
     */
    public void advance(Instant watermark, Instant now) {
        jdbc().sql("UPDATE rt.stats_watermarks SET watermark = :watermark, updated_at = :now WHERE name = :name")
                .param("watermark", JdbcValues.timestamptz(watermark))
                .param("now", JdbcValues.timestamptz(now))
                .param("name", WATERMARK)
                .update();
    }

    /**
     * 删除早于给定时间的小时行。
     *
     * @param before 小时起点早于它的行被删除
     * @return 删除的行数
     */
    public int deleteBefore(Instant before) {
        return jdbc().sql("DELETE FROM rt.agent_turn_stats WHERE hour_start < :before")
                .param("before", JdbcValues.timestamptz(before))
                .update();
    }
}
