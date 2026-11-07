package cn.refinex.meridian.runtime.session.domain;

import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.Status;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import java.util.Map;
import java.util.Objects;

/**
 * 恢复判定看到的一次工具调用：账本记录加上结果摘要（架构设计「崩溃恢复」）。
 *
 * @param invocationId 账本记录 ID；必填
 * @param toolUseId 工具调用 ID；必填
 * @param toolName 工具名；必填
 * @param sideEffect 副作用等级；必填
 * @param status 账本状态；必填
 * @param leaseEpoch 发起调用的那次执行的 epoch
 * @param idempotencyKey 幂等键；{@code non_idempotent} 必有
 * @param input 入参；必填，可为空映射
 * @param resultSummary 结果摘要；只对成功的调用给出，取不到时为空
 */
public record ToolCallRecord(
        String invocationId,
        String toolUseId,
        String toolName,
        SideEffect sideEffect,
        Status status,
        long leaseEpoch,
        String idempotencyKey,
        Map<String, Object> input,
        String resultSummary) {

    /**
     * 创建记录。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public ToolCallRecord {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(sideEffect, "sideEffect");
        Objects.requireNonNull(status, "status");
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
