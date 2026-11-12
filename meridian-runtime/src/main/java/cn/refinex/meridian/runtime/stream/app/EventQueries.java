package cn.refinex.meridian.runtime.stream.app;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.runtime.session.app.SessionService;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import org.springframework.stereotype.Service;

/**
 * 事件的查询（{@code GET /v1/sessions/{id}/events}）：按 seq 升序分页列出会话的持久化事件。线程安全：无状态。
 *
 * <p>事件仓储跨租户（写入入口共用），所以先经项目级的会话仓储确认会话属于当前项目，再按会话读取事件。
 */
@Service
public class EventQueries {

    private final SessionService sessions;
    private final SessionEventReader events;

    /**
     * 创建查询。
     *
     * @param sessions 会话用例，用于确认会话属于当前项目
     * @param events 事件读取入口（透明读取归档事件，ADR-0049）
     */
    public EventQueries(SessionService sessions, SessionEventReader events) {
        this.sessions = sessions;
        this.events = events;
    }

    /**
     * 列出 {@code page.after()} 之后的事件。游标的唯一键是上一页最后一个事件的 seq。
     *
     * @param sessionId 会话 ID
     * @param page 分页参数
     * @return 一页事件
     * @throws cn.refinex.meridian.common.error.NotFoundException 会话不存在时（{@code session.not_found}）
     * @throws ValidationException 游标中的 seq 不是数字时
     */
    public PageResult<StoredEvent> list(String sessionId, PageRequest page) {
        sessions.get(sessionId);
        long afterSeq = page.after() == null ? 0 : seqOf(page.after());
        return PageResult.of(
                events.listAfter(sessionId, afterSeq, page.fetchSize()),
                page,
                event -> Cursor.of(Long.toString(event.seq())));
    }

    private static long seqOf(Cursor cursor) {
        try {
            return Long.parseLong(cursor.key(0));
        } catch (NumberFormatException e) {
            throw new ValidationException(CommonErrorCode.INVALID_CURSOR, "分页游标无效");
        }
    }
}
