package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.engine.spi.engine.ToolRetry;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import java.util.List;

/** 认领后准备的结果：可以交给内核执行，或者已经在准备阶段结束（恢复次数用尽、等待恢复确认、失去租约）。 */
sealed interface Prepared {

    /**
     * 交给内核执行；{@code session.status_running} 已写入。
     *
     * @param context 本轮的上下文
     * @param input 本次执行的输入
     * @param agentVersionId 会话绑定的智能体版本，指标与审计使用
     * @param retries 恢复确认选择 {@code retry} 的调用：执行本轮之前由平台用原幂等键重新执行，结果写进恢复说明（架构设计
     *     「待处理动作」）；只在恢复轮第一次执行时非空
     */
    record Ready(TurnContext context, TurnInput input, String agentVersionId, List<ToolRetry> retries)
            implements Prepared {

        /** 对重试列表做不可变复制。 */
        public Ready {
            retries = retries == null ? List.of() : List.copyOf(retries);
        }
    }

    /**
     * 准备阶段已经结束本次执行。
     *
     * @param result 结果
     * @param agentVersionId 会话绑定的智能体版本；会话不可用时为空
     */
    record Done(TurnResult result, String agentVersionId) implements Prepared {}
}
