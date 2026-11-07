package cn.refinex.meridian.runtime.stream.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.StreamEvent;
import cn.refinex.meridian.persistence.redis.StreamMessage;
import cn.refinex.meridian.runtime.session.domain.SessionStatus;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.stream.domain.StreamFilter;
import cn.refinex.meridian.runtime.stream.infra.system.SessionEventRepository;
import cn.refinex.meridian.runtime.stream.infra.system.SessionEventRepository.SessionProgress;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * SSE 事件流（{@code GET /v1/sessions/{id}/stream} 与线程流），严格按架构设计「SSE 协议」的五步：
 * <ol>
 *   <li>先订阅 Redis Stream {@code ev:{session_id}} 并缓冲（{@link SessionStreamHub}）。
 *   <li>从 PostgreSQL 补发 {@code Last-Event-ID} 之后、直到当前 {@code last_seq} 的事件。
 *   <li>输出缓冲中的事件，按 seq 去重，之后转为实时输出。
 *   <li>发现 seq 跳号，立即从 PostgreSQL 回补缺失的事件。
 *   <li>每 5 秒用 PostgreSQL 中的 {@code last_seq} 对账一次，覆盖"事件已提交、写入方在 XADD 前崩溃"的情况。
 * </ol>
 *
 * <p>去重与跳号按会话的全部序号判断：Redis Stream 里是会话全部线程的事件，连接记录"seq 不超过它的事件都已处理"的位置
 * （{@code covered}），收到 {@code covered + 1} 时前进，更大时视为跳号。线程流与 {@code ?threads=main} 只输出范围内的事件，
 * 其他线程的条目照样推进位置，所以它们占用的序号不会被当成缺失；回补只查询范围内、在 {@code (covered, seq)} 区间的事件。
 *
 * <p>每个连接的信号（补发、Redis 条目、对账）串行处理，连接状态只在处理信号时修改。数据库读取经 {@code Blocking}。
 * 推送 {@code session.status_terminated} 之后关闭连接（线程流不推送它，同样关闭）。线程安全：无状态，连接状态在 {@link Connection} 里。
 */
@Service
public class EventStreamService {

    private static final Logger log = LoggerFactory.getLogger(EventStreamService.class);

    private final SessionStreamHub hub;
    private final SessionEventRepository events;
    private final SseProperties properties;
    private final Clock clock;
    private final AtomicInteger connections = new AtomicInteger();
    private final Counter gapRefills;

    /**
     * 创建事件流服务。
     *
     * @param hub Redis Stream 的读取入口
     * @param events 事件仓储，补发与对账读取数据库
     * @param properties SSE 配置
     * @param clock 时钟，{@code ping} 的时间
     * @param meters 指标注册表
     */
    public EventStreamService(
            SessionStreamHub hub,
            SessionEventRepository events,
            SseProperties properties,
            Clock clock,
            MeterRegistry meters) {
        this.hub = hub;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        Gauge.builder("meridian.sse.connections", connections, AtomicInteger::get)
                .description("当前打开的 SSE 连接数")
                .register(meters);
        this.gapRefills = Counter.builder("meridian.sse.gap.refills")
                .description("SSE 发现缺失的事件（跳号或对账落后）并从数据库回补的次数")
                .register(meters);
    }

    /**
     * 打开一个事件流。调用方已确认会话属于当前项目、线程属于会话。
     *
     * @param sessionId 会话 ID
     * @param filter 订阅范围
     * @param lastEventId 客户端上次收到的最后一个 seq；从头开始时为 0
     * @param eventDeltas 是否推送只在流上的增量事件
     * @return SSE 消息，连接时长到期或会话终止后结束
     */
    public Flux<ServerSentEvent<String>> open(
            String sessionId, StreamFilter filter, long lastEventId, boolean eventDeltas) {
        return hub.open(sessionId).flatMapMany(subscription -> {
            Connection connection = new Connection(sessionId, filter, lastEventId, eventDeltas);
            Flux<Signal> live = Flux.merge(
                    subscription.entries().map(Signal.Entry::new),
                    Flux.interval(properties.reconcileInterval()).map(tick -> new Signal.Reconcile()));
            Flux<Frame> frames =
                    Flux.concat(Mono.just(new Signal.Catchup()), live).concatMap(connection::handle);
            Flux<Frame> pings = Flux.interval(properties.pingInterval()).map(tick -> connection.ping());
            return Flux.merge(frames, pings)
                    .takeUntil(Frame::closes)
                    .take(properties.maxConnectionDuration())
                    .filter(frame -> frame.message() != null)
                    .map(Frame::message)
                    .doOnSubscribe(s -> connections.incrementAndGet())
                    .doFinally(signal -> {
                        connections.decrementAndGet();
                        subscription.close();
                    });
        });
    }

    /** 一个连接要处理的信号。 */
    private sealed interface Signal {

        /** 第 2 步：补发历史事件。 */
        record Catchup() implements Signal {}

        /**
         * 第 3、4 步：Redis Stream 中的一个条目。
         *
         * @param message 条目
         */
        record Entry(StreamMessage message) implements Signal {}

        /** 第 5 步：对账。 */
        record Reconcile() implements Signal {}
    }

    /**
     * 一条待输出的消息。
     *
     * @param message SSE 消息；可为空，表示不输出，只用于结束连接
     * @param closes 输出后是否关闭连接
     */
    private record Frame(ServerSentEvent<String> message, boolean closes) {}

    /** 一个连接的状态：只在串行处理信号时修改。 */
    private final class Connection {

        private final String sessionId;
        private final StreamFilter filter;
        private final boolean eventDeltas;
        private long covered;

        Connection(String sessionId, StreamFilter filter, long lastEventId, boolean eventDeltas) {
            this.sessionId = sessionId;
            this.filter = filter;
            this.covered = lastEventId;
            this.eventDeltas = eventDeltas;
        }

        Flux<Frame> handle(Signal signal) {
            return switch (signal) {
                case Signal.Catchup _ -> catchup();
                case Signal.Entry entry -> entry(entry.message());
                case Signal.Reconcile _ -> reconcile();
            };
        }

        private Flux<Frame> catchup() {
            return Blocking.call(() -> {
                        SessionProgress progress = events.progress(sessionId).orElse(null);
                        if (progress == null) {
                            return List.of(new Frame(null, true));
                        }
                        List<Frame> frames = refill(progress.lastSeq());
                        if (progress.status() == SessionStatus.TERMINATED && covered >= progress.lastSeq()) {
                            // 会话已终止且客户端已收到终止事件：没有更多事件了
                            frames.add(new Frame(null, true));
                        }
                        return frames;
                    })
                    .flatMapIterable(frames -> frames);
        }

        private Flux<Frame> entry(StreamMessage message) {
            String seqField = message.field("seq");
            if (seqField == null) {
                return Flux.fromIterable(transientFrame(message));
            }
            long seq = Long.parseLong(seqField);
            if (seq <= covered) {
                return Flux.empty();
            }
            Frame live = persistedFrame(message, seq);
            if (seq == covered + 1) {
                covered = seq;
                return Flux.just(live);
            }
            // 第 4 步：跳号。比 seq 小的事件都已提交（seq 在会话行锁内分配，提交顺序与 seq 一致），回补一定完整
            long gapEnd = seq - 1;
            return Blocking.call(() -> {
                        gapRefills.increment();
                        log.debug("SSE 跳号，从数据库回补 sessionId={} from={} to={}", sessionId, covered + 1, gapEnd);
                        List<Frame> frames = refill(gapEnd);
                        covered = seq;
                        frames.add(live);
                        return frames;
                    })
                    .flatMapIterable(frames -> frames);
        }

        private Flux<Frame> reconcile() {
            return Blocking.call(() -> {
                        SessionProgress progress = events.progress(sessionId).orElse(null);
                        if (progress == null) {
                            return List.of(new Frame(null, true));
                        }
                        if (progress.lastSeq() <= covered) {
                            return List.<Frame>of();
                        }
                        gapRefills.increment();
                        log.debug(
                                "SSE 对账发现落后，从数据库补发 sessionId={} covered={} lastSeq={}",
                                sessionId,
                                covered,
                                progress.lastSeq());
                        return refill(progress.lastSeq());
                    })
                    .flatMapIterable(frames -> frames);
        }

        /** 从数据库读取 {@code (covered, upto]} 内范围内的事件并推进位置。在虚拟线程上调用。 */
        private List<Frame> refill(long upto) {
            List<Frame> frames = new ArrayList<>();
            while (covered < upto) {
                List<StoredEvent> batch = events.listRange(sessionId, covered, upto, filter, properties.refillBatch());
                for (StoredEvent event : batch) {
                    frames.add(storedFrame(event));
                }
                if (batch.size() < properties.refillBatch()) {
                    covered = upto;
                } else {
                    covered = batch.getLast().seq();
                }
            }
            return frames;
        }

        private Frame storedFrame(StoredEvent event) {
            boolean terminal = event.type() == EventType.SESSION_STATUS_TERMINATED;
            if (!filter.accepts(event.threadId(), event.type())) {
                return new Frame(null, terminal);
            }
            return new Frame(sse(event.type().value(), event.seq(), EventEnvelopes.json(event)), terminal);
        }

        private Frame persistedFrame(StreamMessage message, long seq) {
            EventType type = EventType.fromValue(Objects.requireNonNull(message.field("type")));
            String envelope = message.field("envelope");
            boolean terminal = type == EventType.SESSION_STATUS_TERMINATED;
            if (!filter.accepts(threadIdOf(envelope), type)) {
                return new Frame(null, terminal);
            }
            return new Frame(sse(type.value(), seq, envelope), terminal);
        }

        private List<Frame> transientFrame(StreamMessage message) {
            if (!eventDeltas) {
                return List.of();
            }
            EventType type = EventType.fromValue(Objects.requireNonNull(message.field("type")));
            String envelope = message.field("envelope");
            if (!filter.accepts(threadIdOf(envelope), type)) {
                return List.of();
            }
            return List.of(new Frame(
                    ServerSentEvent.builder(envelope).event(type.value()).build(), false));
        }

        Frame ping() {
            String envelope =
                    EventEnvelopes.transientJson(sessionId, null, null, new StreamEvent.Ping(), clock.instant());
            return new Frame(
                    ServerSentEvent.builder(envelope)
                            .event(EventType.PING.value())
                            .build(),
                    false);
        }

        private static ServerSentEvent<String> sse(String type, long seq, String envelope) {
            return ServerSentEvent.builder(envelope)
                    .event(type)
                    .id(Long.toString(seq))
                    .build();
        }

        private static String threadIdOf(String envelope) {
            JsonNode threadId = JsonMappers.lenient().readTree(envelope).get("thread_id");
            return threadId == null || threadId.isNull() ? null : threadId.asString();
        }
    }
}
