package cn.refinex.meridian.engine.agentscope.turn;

import java.util.Objects;

/**
 * 平台重新执行一个工具调用时放进 {@code RuntimeContext} 的类型键：带上账本中记录的原幂等键（架构设计「待处理动作」中恢复确认的
 * {@code retry}）。平台工具基类（T10a）记账时优先使用它，而不是重新生成幂等键，外部系统据此去重。
 *
 * @param idempotencyKey 原幂等键；必填
 */
public record ToolRetryContext(String idempotencyKey) {

    /**
     * 创建重试上下文。
     *
     * @throws NullPointerException 缺少幂等键时
     */
    public ToolRetryContext {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
