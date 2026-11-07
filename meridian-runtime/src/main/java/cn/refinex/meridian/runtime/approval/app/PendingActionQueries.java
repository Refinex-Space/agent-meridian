package cn.refinex.meridian.runtime.approval.app;

import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.runtime.approval.domain.PendingActionStatus;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import cn.refinex.meridian.runtime.approval.infra.PendingActionRepository;
import cn.refinex.meridian.runtime.session.app.SessionService;
import org.springframework.stereotype.Service;

/**
 * 待处理动作的查询（{@code GET /v1/sessions/{id}/pending-actions}）。会话不属于当前项目时与不存在相同。线程安全：无状态。
 */
@Service
public class PendingActionQueries {

    private final SessionService sessions;
    private final PendingActionRepository repository;

    /**
     * 创建查询。
     *
     * @param sessions 会话用例，用于确认会话存在
     * @param repository 待处理动作仓储
     */
    public PendingActionQueries(SessionService sessions, PendingActionRepository repository) {
        this.sessions = sessions;
        this.repository = repository;
    }

    /**
     * 按创建顺序列出会话中某个状态的动作。
     *
     * @param sessionId 会话 ID
     * @param status 状态
     * @param page 分页参数
     * @return 一页动作
     * @throws cn.refinex.meridian.common.error.NotFoundException 会话不存在时（{@code session.not_found}）
     */
    public PageResult<StoredPendingAction> list(String sessionId, PendingActionStatus status, PageRequest page) {
        sessions.get(sessionId);
        return PageResult.of(repository.page(sessionId, status, page), page, action -> Cursor.of(action.id()));
    }
}
