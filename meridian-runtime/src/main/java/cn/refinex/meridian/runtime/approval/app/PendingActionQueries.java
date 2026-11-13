package cn.refinex.meridian.runtime.approval.app;

import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.runtime.approval.domain.PendingActionFilter;
import cn.refinex.meridian.runtime.approval.domain.PendingActionStatus;
import cn.refinex.meridian.runtime.approval.domain.PendingActionView;
import cn.refinex.meridian.runtime.approval.infra.PendingActionRepository;
import cn.refinex.meridian.runtime.session.app.SessionService;
import org.springframework.stereotype.Service;

/**
 * 待处理动作的查询（{@code GET /v1/sessions/{id}/pending-actions} 与项目级的 {@code GET /v1/pending-actions}）。会话不属于当前项目时与不存在相同。线程安全：无状态。
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
    public PageResult<PendingActionView> list(String sessionId, PendingActionStatus status, PageRequest page) {
        sessions.get(sessionId);
        return PageResult.of(
                repository.sessionViews(sessionId, status, page),
                page,
                view -> Cursor.of(view.action().id()));
    }

    /**
     * 列出当前项目全部未删除会话的动作，新创建的在前（{@code GET /v1/pending-actions}，ADR-0053）。
     *
     * @param filter 筛选条件
     * @param page 分页参数
     * @return 一页动作
     */
    public PageResult<PendingActionView> listProject(PendingActionFilter filter, PageRequest page) {
        return PageResult.of(
                repository.projectViews(filter, page),
                page,
                view -> Cursor.of(view.action().id()));
    }
}
