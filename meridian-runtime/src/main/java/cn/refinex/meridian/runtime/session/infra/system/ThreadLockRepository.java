package cn.refinex.meridian.runtime.session.infra.system;

import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 按线程加事务级咨询锁，串行化"消息进入收件箱"与"轮次结束时合并收件箱"（ADR-0028）。
 *
 * <p>没有这把锁时会丢消息：API 插入下一轮时发现线程仍有执行中的轮次（插入为空操作），而 Worker 结束轮次时还看不到这条尚未提交的
 * 消息，两边都没有为它生成轮次。API 写 {@code user.message} 的事务（T07）与 Worker 结束轮次的事务都先取这把锁。
 * 回收器不取它，它与这两个事务之间的加锁顺序仍按 ADR-0027 第 3 条，所以不会形成死锁。
 */
@Repository
@CrossTenant(reason = "按线程 ID 加事务级咨询锁，不读写任何表；调用方已经持有该线程所属轮次的租约或会话的访问权")
public class ThreadLockRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public ThreadLockRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 在当前事务中锁住线程，事务结束时自动释放。必须在事务中调用。
     *
     * @param threadId 线程 ID
     */
    public void lock(String threadId) {
        jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:thread_id, 0))")
                .param("thread_id", threadId)
                .query((rs, row) -> 1)
                .single();
    }

    /**
     * 尝试在当前事务中锁住线程，不等待。持有其他行锁的后台作业（例如待处理动作的超时扫描）用它，避免与"先取咨询锁、再锁行"的
     * 事务形成死锁。必须在事务中调用。
     *
     * @param threadId 线程 ID
     * @return 拿到锁时为 true；其他事务正持有时为 false
     */
    public boolean tryLock(String threadId) {
        return Boolean.TRUE.equals(jdbc().sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:thread_id, 0))")
                .param("thread_id", threadId)
                .query(Boolean.class)
                .single());
    }
}
