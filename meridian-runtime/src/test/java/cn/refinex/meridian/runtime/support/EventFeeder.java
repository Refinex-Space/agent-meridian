package cn.refinex.meridian.runtime.support;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.StreamEvent;
import cn.refinex.meridian.runtime.stream.app.EventStreamPublisher;
import cn.refinex.meridian.runtime.stream.app.SessionEventWriter;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import cn.refinex.meridian.runtime.stream.infra.system.SessionEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在测试中扮演写入事件的 Worker：经唯一写入入口写入（提交后 XADD），或只写数据库不 XADD（模拟"事件已提交、Worker 在 XADD 前
 * 被取消"），或只发布只在流上的事件。
 */
public final class EventFeeder {

    private final SessionEventWriter writer;
    private final SessionEventRepository repository;
    private final EventStreamPublisher publisher;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 从 Spring 容器取得写入所需的组件。
     *
     * @param context 应用容器
     */
    public EventFeeder(ApplicationContext context) {
        this.writer = context.getBean(SessionEventWriter.class);
        this.repository = context.getBean(SessionEventRepository.class);
        this.publisher = context.getBean(EventStreamPublisher.class);
        this.transactions = context.getBean(TransactionTemplate.class);
        this.clock = context.getBean(Clock.class);
    }

    /**
     * 经写入入口写入一条智能体消息，提交后 XADD。
     *
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param text 文本
     */
    public void published(String sessionId, String threadId, String text) {
        writer.append(EventBatch.unfenced(sessionId, threadId, List.of(message(text))));
    }

    /**
     * 只写数据库、不 XADD：模拟 Worker 在事务提交之后、XADD 之前被取消。
     *
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param text 文本
     */
    public void committedWithoutXadd(String sessionId, String threadId, String text) {
        EngineEvent event = message(text);
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            SessionEventRepository.SeqAllocation allocation =
                    repository.allocate(sessionId, 1, now).orElseThrow();
            repository.insert(new StoredEvent(
                    sessionId,
                    allocation.firstSeq(),
                    Ids.newId(IdPrefix.EVENT),
                    allocation.tenantId(),
                    allocation.projectId(),
                    threadId,
                    null,
                    event.type(),
                    EngineJson.writeEvent(event),
                    now));
        });
    }

    /**
     * 发布一个只在流上的增量事件。
     *
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param delta 增量文本
     */
    public void delta(String sessionId, String threadId, String delta) {
        publisher
                .publishTransient(
                        sessionId,
                        threadId,
                        null,
                        new StreamEvent.EventDelta("rpl_1:text", StreamEvent.BlockKind.TEXT, delta))
                .block();
    }

    private static EngineEvent message(String text) {
        return new AgentOutputEvent.Message(Ids.newUlid(), List.of(new MessageContent.Text(text)));
    }
}
