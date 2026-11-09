package cn.refinex.meridian.controlplane.web;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 在当前事务提交后执行一个动作（Pub/Sub 通知只在数据可见之后发出）；没有事务时立即执行。 */
public final class AfterCommit {

    private AfterCommit() {}

    /**
     * 登记一个提交后动作。
     *
     * @param action 动作，不应抛异常（提交已完成，异常无法回滚任何东西）
     */
    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
