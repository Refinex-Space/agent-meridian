package cn.refinex.meridian.runtime.approval.domain;

/**
 * 公开 API 中的待处理动作（ADR-0053）：动作本身，加上会话绑定的智能体、回应者与结论，供控制台的待处理页与历史使用。
 *
 * @param action 动作
 * @param agentId 会话绑定的智能体 ID；T19b 之前创建的会话为空
 * @param decidedBy 回应者（API Key ID 或控制台用户标识）；平台给出的结论与仍在等待时为空
 * @param conclusion 结论；仍在等待、或随会话终止作废时为空
 */
public record PendingActionView(
        StoredPendingAction action, String agentId, String decidedBy, ActionConclusion conclusion) {}
