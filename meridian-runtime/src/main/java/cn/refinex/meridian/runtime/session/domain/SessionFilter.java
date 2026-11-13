package cn.refinex.meridian.runtime.session.domain;

import java.time.Instant;

/**
 * 会话列表的筛选条件（{@code GET /v1/sessions}，ADR-0053）：条件之间是"且"，为空的条件不过滤；创建时间的范围左闭右开。
 *
 * @param endUserId 终端用户；可为空
 * @param agentId 智能体 ID；可为空
 * @param status 会话状态；可为空
 * @param createdAfter 创建时间不早于它；可为空
 * @param createdBefore 创建时间早于它；可为空
 */
public record SessionFilter(
        String endUserId, String agentId, SessionStatus status, Instant createdAfter, Instant createdBefore) {

    /** 不过滤。 */
    public static final SessionFilter NONE = new SessionFilter(null, null, null, null, null);

    /**
     * 只按终端用户过滤。
     *
     * @param endUserId 终端用户；可为空
     * @return 条件
     */
    public static SessionFilter endUser(String endUserId) {
        return new SessionFilter(endUserId, null, null, null, null);
    }
}
