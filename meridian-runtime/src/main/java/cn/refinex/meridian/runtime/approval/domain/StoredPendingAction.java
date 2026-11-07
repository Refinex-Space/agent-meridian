package cn.refinex.meridian.runtime.approval.domain;

import cn.refinex.meridian.engine.spi.event.PendingAction;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读出的待处理动作（{@code rt.pending_actions} 的一行）。
 *
 * @param id 动作 ID（{@code act_…}）
 * @param sessionId 所属会话
 * @param threadId 产生动作的线程
 * @param turnId 产生动作的轮次
 * @param type 动作类型
 * @param status 状态
 * @param toolUseId 相关的工具调用 ID
 * @param toolName 相关的工具名
 * @param input 相关工具调用的入参；可为空映射
 * @param expiresAt 截止时间
 * @param createdAt 创建时间
 * @param resolvedAt 回应或超时的时间；可为空，表示仍在等待
 */
public record StoredPendingAction(
        String id,
        String sessionId,
        String threadId,
        String turnId,
        PendingAction.Type type,
        PendingActionStatus status,
        String toolUseId,
        String toolName,
        Map<String, Object> input,
        Instant expiresAt,
        Instant createdAt,
        Instant resolvedAt) {

    /** 对入参做不可变复制；入参来自模型输出，可能含 null 值。 */
    public StoredPendingAction {
        input = input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    /**
     * 是否仍在等待回应。
     *
     * @return 状态为 pending 时为 true
     */
    public boolean isPending() {
        return status == PendingActionStatus.PENDING;
    }
}
