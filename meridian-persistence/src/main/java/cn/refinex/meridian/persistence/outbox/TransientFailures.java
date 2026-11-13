package cn.refinex.meridian.persistence.outbox;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 瞬时错误的判定：数据库或 Redis 不可用、超时、事务无法开启。outbox 投递遇到它不计消息的失败次数（发布目标不可用不是消息的问题），
 * Stream 消费者遇到它不转死信；两处共用这一个判定（R2，审查 K-03）。
 */
public final class TransientFailures {

    private TransientFailures() {}

    /**
     * 是否为瞬时错误。
     *
     * @param error 异常
     * @return 原因链上有瞬时错误时为 true
     */
    public static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof TransientDataAccessException
                    || t instanceof RecoverableDataAccessException
                    || t instanceof DataAccessResourceFailureException
                    || t instanceof QueryTimeoutException
                    || t instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }
}
