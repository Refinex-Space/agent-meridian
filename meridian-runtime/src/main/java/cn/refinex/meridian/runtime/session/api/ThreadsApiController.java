package cn.refinex.meridian.runtime.session.api;

import cn.refinex.meridian.api.model.ThreadPage;
import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.runtime.openapi.ThreadsApi;
import cn.refinex.meridian.runtime.session.app.SessionService;
import cn.refinex.meridian.security.apikey.ApiKeyAuthorization;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 线程接口（{@code GET /v1/sessions/{id}/threads}）；线程流在 {@code stream.api}。 */
@RestController
public class ThreadsApiController implements ThreadsApi {

    private final SessionService sessions;

    /**
     * 创建处理器。
     *
     * @param sessions 会话用例
     */
    public ThreadsApiController(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public Mono<ResponseEntity<ThreadPage>> listThreads(
            String sessionId, Integer limit, String after, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        PageRequest page = PageRequest.of(limit, after);
        return Blocking.call(() -> SessionMapper.toThreadPage(sessions.threads(sessionId, page)))
                .map(ResponseEntity::ok);
    }
}
