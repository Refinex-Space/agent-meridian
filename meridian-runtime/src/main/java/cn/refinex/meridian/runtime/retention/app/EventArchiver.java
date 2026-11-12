package cn.refinex.meridian.runtime.retention.app;

import cn.refinex.meridian.runtime.retention.infra.system.SessionLifecycleRepository;
import cn.refinex.meridian.runtime.retention.infra.system.SessionLifecycleRepository.ArchiveState;
import cn.refinex.meridian.runtime.stream.app.EventArchives;
import cn.refinex.meridian.runtime.stream.app.SessionEventReader;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 事件归档作业（架构设计「数据保留」：会话 90 天无活动后，事件导出为对象存储中的文件，数据库中删除，ADR-0049）。
 *
 * <p>在会话行锁内（{@code SKIP LOCKED}，同一会话的事件写入等待）把该会话全部现存的事件——已有归档文件中的与数据库中的——按 seq 写成一个
 * 新文件，同一事务删除数据库中的事件并推进 {@code events_archived_seq}，提交后删除被取代的旧文件。归档之后会话照常可用，新事件写回
 * 数据库，再次满足条件时合并归档。事务失败时删除刚写的文件，下一轮重试。
 *
 * <p>线程安全：无状态；方法会阻塞。
 */
@Service
public class EventArchiver {

    /** 读取事件的批大小。 */
    static final int READ_BATCH = 5_000;

    private static final Logger log = LoggerFactory.getLogger(EventArchiver.class);

    private final SessionLifecycleRepository sessions;
    private final SessionEventReader events;
    private final EventArchives archives;
    private final RetentionProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建作业。
     *
     * @param sessions 跨租户的会话仓储
     * @param events 事件读取入口（合并已有的归档文件与数据库）
     * @param archives 归档文件
     * @param properties 配置
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public EventArchiver(
            SessionLifecycleRepository sessions,
            SessionEventReader events,
            EventArchives archives,
            RetentionProperties properties,
            TransactionTemplate transactions,
            Clock clock) {
        this.sessions = sessions;
        this.events = events;
        this.archives = archives;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 扫描一次。单个会话失败只记日志，下次重试。
     *
     * @return 本次归档的会话数
     */
    public int archiveOnce() {
        Instant cutoff = clock.instant().minus(properties.archiveAfter());
        List<String> candidates =
                transactions.execute(status -> sessions.archiveCandidates(cutoff, properties.archiveBatchSize()));
        int archived = 0;
        for (String sessionId : candidates == null ? List.<String>of() : candidates) {
            try {
                if (archive(sessionId, cutoff)) {
                    archived++;
                }
            } catch (RuntimeException e) {
                log.warn("归档会话事件失败，下次重试 sessionId={}", sessionId, e);
            }
        }
        return archived;
    }

    /**
     * 归档一个会话的事件。
     *
     * @param sessionId 会话 ID
     * @param cutoff 无活动的截止时间
     * @return 归档了时为 true；不再满足条件或被锁住时为 false
     */
    boolean archive(String sessionId, Instant cutoff) {
        AtomicReference<String> written = new AtomicReference<>();
        AtomicReference<String> superseded = new AtomicReference<>();
        Boolean done;
        try {
            done = transactions.execute(status -> {
                Optional<ArchiveState> locked = sessions.lockForArchive(sessionId, cutoff);
                if (locked.isEmpty()) {
                    return false;
                }
                ArchiveState state = locked.get();
                List<StoredEvent> all = readAll(sessionId);
                String key = all.isEmpty() ? null : archives.write(state.tenantId(), sessionId, state.lastSeq(), all);
                written.set(key);
                int deleted = sessions.deleteEventsUpTo(sessionId, state.lastSeq());
                sessions.markArchived(
                        sessionId,
                        key,
                        state.lastSeq(),
                        all.isEmpty() ? null : all.getFirst().createdAt(),
                        clock.instant());
                superseded.set(state.archiveKey());
                log.info(
                        "会话事件已归档 sessionId={} events={} fromDatabase={} archivedSeq={} key={}",
                        sessionId,
                        all.size(),
                        deleted,
                        state.lastSeq(),
                        key);
                return true;
            });
        } catch (RuntimeException e) {
            archives.delete(written.get());
            throw e;
        }
        if (Boolean.TRUE.equals(done)) {
            archives.delete(superseded.get());
            return true;
        }
        return false;
    }

    /** 读出会话全部现存的事件：已有归档文件中的与数据库中的，按 seq 升序。在持有会话行锁的事务中调用。 */
    private List<StoredEvent> readAll(String sessionId) {
        List<StoredEvent> all = new ArrayList<>();
        long after = 0;
        while (true) {
            List<StoredEvent> batch = events.listAfter(sessionId, after, READ_BATCH);
            all.addAll(batch);
            if (batch.size() < READ_BATCH) {
                return all;
            }
            after = batch.getLast().seq();
        }
    }
}
