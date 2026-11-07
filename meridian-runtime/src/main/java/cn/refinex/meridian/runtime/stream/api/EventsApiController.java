package cn.refinex.meridian.runtime.stream.api;

import cn.refinex.meridian.api.model.EventPage;
import cn.refinex.meridian.api.model.SendEventsRequest;
import cn.refinex.meridian.api.model.SendEventsResponse;
import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.runtime.delivery.app.EventIntake;
import cn.refinex.meridian.runtime.openapi.EventsApi;
import cn.refinex.meridian.runtime.session.app.SessionService;
import cn.refinex.meridian.runtime.stream.app.EventQueries;
import cn.refinex.meridian.runtime.stream.app.EventStreamService;
import cn.refinex.meridian.runtime.stream.domain.StreamFilter;
import cn.refinex.meridian.runtime.web.IdempotentRequests;
import cn.refinex.meridian.security.apikey.ApiKeyAuthorization;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 事件接口：发送与列出事件（{@code /v1/sessions/{id}/events}），会话流与线程流（SSE）。 */
@RestController
public class EventsApiController implements EventsApi {

    /** {@code ?threads=main} 的取值。 */
    static final String MAIN_THREADS = "main";

    private final EventIntake intake;
    private final EventQueries queries;
    private final EventStreamService streams;
    private final SessionService sessions;
    private final IdempotentRequests idempotent;

    /**
     * 创建处理器。
     *
     * @param intake 发送事件的用例
     * @param queries 事件查询
     * @param streams SSE 事件流
     * @param sessions 会话用例，打开事件流前确认会话与线程
     * @param idempotent POST 的幂等执行
     */
    public EventsApiController(
            EventIntake intake,
            EventQueries queries,
            EventStreamService streams,
            SessionService sessions,
            IdempotentRequests idempotent) {
        this.intake = intake;
        this.queries = queries;
        this.streams = streams;
        this.sessions = sessions;
        this.idempotent = idempotent;
    }

    @Override
    public Mono<ResponseEntity<SendEventsResponse>> sendEvents(
            String sessionId,
            Mono<SendEventsRequest> sendEventsRequest,
            String idempotencyKey,
            ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_WRITE);
        return sendEventsRequest.flatMap(request -> idempotent.execute(
                exchange,
                idempotencyKey,
                request,
                HttpStatus.ACCEPTED,
                SendEventsResponse.class,
                () -> EventMapper.toResponse(intake.send(sessionId, EventMapper.toEvents(request.getEvents())))));
    }

    @Override
    public Mono<ResponseEntity<EventPage>> listEvents(
            String sessionId, Integer limit, String after, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        PageRequest page = PageRequest.of(limit, after);
        return Blocking.call(() -> EventMapper.toPage(queries.list(sessionId, page)))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> streamSessionEvents(
            String sessionId, String lastEventID, Boolean eventDeltas, String threads, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        long lastSeq = lastEventID == null ? 0 : Long.parseLong(lastEventID);
        return Blocking.call(() -> {
                    // 先确认会话属于当前项目：其他项目的会话与不存在相同（架构设计「业务接入示例」）
                    sessions.get(sessionId);
                    return MAIN_THREADS.equals(threads)
                            ? (StreamFilter) new StreamFilter.Main(
                                    sessions.mainThread(sessionId).id())
                            : new StreamFilter.All();
                })
                .map(filter -> stream(streams.open(sessionId, filter, lastSeq, Boolean.TRUE.equals(eventDeltas))));
    }

    @Override
    public Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> streamThreadEvents(
            String sessionId, String threadId, String lastEventID, Boolean eventDeltas, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        long lastSeq = lastEventID == null ? 0 : Long.parseLong(lastEventID);
        return Blocking.call(() -> new StreamFilter.Thread(
                        sessions.thread(sessionId, threadId).id()))
                .map(filter -> stream(streams.open(sessionId, filter, lastSeq, Boolean.TRUE.equals(eventDeltas))));
    }

    private static ResponseEntity<Flux<ServerSentEvent<String>>> stream(Flux<ServerSentEvent<String>> events) {
        // 关闭代理缓冲（nginx 的 X-Accel-Buffering），事件立即到达客户端
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(events);
    }
}
