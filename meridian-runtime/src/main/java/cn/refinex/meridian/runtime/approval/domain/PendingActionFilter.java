package cn.refinex.meridian.runtime.approval.domain;

import cn.refinex.meridian.engine.spi.event.PendingAction;

/**
 * 项目级待处理动作列表的筛选条件（{@code GET /v1/pending-actions}，ADR-0053）：条件之间是"且"，为空的条件不过滤。
 *
 * @param status 状态；可为空，表示全部状态
 * @param type 类型；可为空
 * @param agentId 会话绑定的智能体；可为空
 * @param sessionId 会话；可为空
 */
public record PendingActionFilter(
        PendingActionStatus status, PendingAction.Type type, String agentId, String sessionId) {}
