package cn.refinex.meridian.runtime.stream.app;

import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.stream.domain.StreamFilter;
import cn.refinex.meridian.runtime.stream.infra.system.SessionEventRepository;
import cn.refinex.meridian.runtime.stream.infra.system.SessionEventRepository.ArchivePointer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 读取持久化事件的唯一入口（{@code GET /v1/sessions/{id}/events}、SSE 补发与对账，ADR-0049）：对调用方透明地合并归档文件与
 * {@code rt.session_events}。seq 不超过会话的 {@code events_archived_seq} 的部分读归档文件（按键缓存），之后的读数据库；过了保留期的事件
 * 两边都没有。
 *
 * <p>归档作业在一个事务里删除数据库中的事件并推进指针：读指针与读数据库放在同一个可重复读的只读事务中，看到的是同一个快照，不会漏掉
 * 刚被归档的事件。快照中的旧文件可能已被新的归档取代并删除，这时重读一次。线程安全：无状态；方法会阻塞。
 */
@Service
public class SessionEventReader {

    private final SessionEventRepository repository;
    private final EventArchives archives;
    private final TransactionTemplate snapshot;

    /**
     * 创建读取入口。
     *
     * @param repository 事件仓储
     * @param archives 归档文件
     * @param transactions 事务管理器，读取用可重复读的只读事务
     */
    public SessionEventReader(
            SessionEventRepository repository, EventArchives archives, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.archives = archives;
        this.snapshot = new TransactionTemplate(transactions);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
    }

    /**
     * 按 seq 顺序读取 {@code afterSeq} 之后的事件。
     *
     * @param sessionId 会话 ID
     * @param afterSeq 起点（不含），从头读取时为 0
     * @param limit 最多返回的条数
     * @return 事件，按 seq 升序
     */
    public List<StoredEvent> listAfter(String sessionId, long afterSeq, int limit) {
        return read(sessionId, afterSeq, Long.MAX_VALUE, null, limit);
    }

    /**
     * 按 seq 顺序读取 {@code (afterSeq, uptoSeq]} 区间内、在订阅范围内的事件；{@code session.status_terminated} 总在结果中（与
     * {@link SessionEventRepository#listRange} 相同）。
     *
     * @param sessionId 会话 ID
     * @param afterSeq 起点（不含）
     * @param uptoSeq 终点（含）
     * @param filter 订阅范围
     * @param limit 最多返回的条数
     * @return 事件，按 seq 升序
     */
    public List<StoredEvent> listRange(String sessionId, long afterSeq, long uptoSeq, StreamFilter filter, int limit) {
        return read(sessionId, afterSeq, uptoSeq, filter, limit);
    }

    private List<StoredEvent> read(String sessionId, long afterSeq, long uptoSeq, StreamFilter filter, int limit) {
        for (int attempt = 0; ; attempt++) {
            Optional<List<StoredEvent>> events =
                    snapshot.execute(status -> readInSnapshot(sessionId, afterSeq, uptoSeq, filter, limit));
            if (events != null && events.isPresent()) {
                return events.get();
            }
            if (attempt >= 1) {
                throw new IllegalStateException("会话的归档文件读不到：" + sessionId);
            }
        }
    }

    /** 在一个快照中读取；快照中的归档文件已被删除时返回空，由调用方重读。 */
    private Optional<List<StoredEvent>> readInSnapshot(
            String sessionId, long afterSeq, long uptoSeq, StreamFilter filter, int limit) {
        ArchivePointer pointer = repository.archivePointer(sessionId).orElse(null);
        if (pointer == null || afterSeq >= pointer.archivedSeq()) {
            return Optional.of(fromDatabase(sessionId, afterSeq, uptoSeq, filter, limit));
        }
        List<StoredEvent> result = new ArrayList<>();
        if (pointer.archiveKey() != null) {
            Optional<List<StoredEvent>> archived =
                    archives.read(pointer.archiveKey(), pointer.tenantId(), pointer.projectId());
            if (archived.isEmpty()) {
                return Optional.empty();
            }
            long end = Math.min(uptoSeq, pointer.archivedSeq());
            for (StoredEvent event : archived.get()) {
                if (result.size() >= limit) {
                    return Optional.of(result);
                }
                if (event.seq() > afterSeq && event.seq() <= end && accepts(filter, event)) {
                    result.add(event);
                }
            }
        }
        if (result.size() < limit && uptoSeq > pointer.archivedSeq()) {
            result.addAll(fromDatabase(sessionId, pointer.archivedSeq(), uptoSeq, filter, limit - result.size()));
        }
        return Optional.of(result);
    }

    private List<StoredEvent> fromDatabase(
            String sessionId, long afterSeq, long uptoSeq, StreamFilter filter, int limit) {
        return filter == null
                ? repository.listAfter(sessionId, afterSeq, limit)
                : repository.listRange(sessionId, afterSeq, uptoSeq, filter, limit);
    }

    /** 与 {@link SessionEventRepository#listRange} 的条件相同。 */
    private static boolean accepts(StreamFilter filter, StoredEvent event) {
        return filter == null
                || event.type() == EventType.SESSION_STATUS_TERMINATED
                || filter.accepts(event.threadId(), event.type());
    }
}
