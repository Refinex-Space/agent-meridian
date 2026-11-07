package cn.refinex.meridian.runtime.session.api;

import cn.refinex.meridian.api.model.CreateSessionRequest;
import cn.refinex.meridian.api.model.Session;
import cn.refinex.meridian.api.model.SessionPage;
import cn.refinex.meridian.api.model.UpdateSessionRequest;
import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.runtime.openapi.SessionsApi;
import cn.refinex.meridian.runtime.session.app.SessionService;
import cn.refinex.meridian.runtime.web.IdempotentRequests;
import cn.refinex.meridian.security.apikey.ApiKeyAuthorization;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 会话接口（{@code /v1/sessions}）：创建、查询、列表、更新、删除、归档。 */
@RestController
public class SessionsApiController implements SessionsApi {

    private final SessionService sessions;
    private final IdempotentRequests idempotent;

    /**
     * 创建处理器。
     *
     * @param sessions 会话用例
     * @param idempotent POST 的幂等执行
     */
    public SessionsApiController(SessionService sessions, IdempotentRequests idempotent) {
        this.sessions = sessions;
        this.idempotent = idempotent;
    }

    @Override
    public Mono<ResponseEntity<Session>> createSession(
            Mono<CreateSessionRequest> createSessionRequest, String idempotencyKey, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_WRITE);
        return createSessionRequest.flatMap(request -> idempotent.execute(
                exchange,
                idempotencyKey,
                request,
                HttpStatus.CREATED,
                Session.class,
                () -> SessionMapper.toDto(sessions.create(SessionMapper.toCommand(request)))));
    }

    @Override
    public Mono<ResponseEntity<SessionPage>> listSessions(
            Integer limit, String after, String endUserId, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        PageRequest page = PageRequest.of(limit, after);
        return Blocking.call(() -> SessionMapper.toPage(sessions.list(page, endUserId)))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<Session>> getSession(String sessionId, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        return Blocking.call(() -> SessionMapper.toDto(sessions.get(sessionId))).map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<Session>> updateSession(
            String sessionId, Mono<UpdateSessionRequest> updateSessionRequest, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_WRITE);
        return updateSessionRequest
                .flatMap(request -> Blocking.call(() -> SessionMapper.toDto(
                        request.getMetadata() == null
                                ? sessions.get(sessionId)
                                : sessions.updateMetadata(sessionId, request.getMetadata()))))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<Session>> deleteSession(String sessionId, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_WRITE);
        return Blocking.call(() -> SessionMapper.toDto(sessions.delete(sessionId)))
                .map(session -> ResponseEntity.status(HttpStatus.ACCEPTED).body(session));
    }

    @Override
    public Mono<ResponseEntity<Session>> archiveSession(
            String sessionId, String idempotencyKey, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_WRITE);
        return idempotent.execute(
                exchange,
                idempotencyKey,
                null,
                HttpStatus.OK,
                Session.class,
                () -> SessionMapper.toDto(sessions.archive(sessionId)));
    }
}
