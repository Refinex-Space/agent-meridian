package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;

/** 认领后准备的结果：可以交给内核执行，或者已经在准备阶段结束（恢复次数用尽、等待恢复确认、失去租约）。 */
sealed interface Prepared {

    /**
     * 交给内核执行；{@code session.status_running} 已写入。
     *
     * @param context 本轮的上下文
     * @param input 本次执行的输入
     * @param agentVersionId 会话绑定的智能体版本，指标与审计使用
     */
    record Ready(TurnContext context, TurnInput input, String agentVersionId) implements Prepared {}

    /**
     * 准备阶段已经结束本次执行。
     *
     * @param result 结果
     * @param agentVersionId 会话绑定的智能体版本；会话不可用时为空
     */
    record Done(TurnResult result, String agentVersionId) implements Prepared {}
}
