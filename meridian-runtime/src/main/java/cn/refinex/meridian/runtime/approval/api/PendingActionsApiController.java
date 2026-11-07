package cn.refinex.meridian.runtime.approval.api;

import cn.refinex.meridian.api.model.PendingAction;
import cn.refinex.meridian.api.model.PendingActionPage;
import cn.refinex.meridian.api.model.PendingActionStatus;
import cn.refinex.meridian.api.model.PendingActionType;
import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.runtime.approval.app.PendingActionQueries;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import cn.refinex.meridian.runtime.openapi.PendingActionsApi;
import cn.refinex.meridian.runtime.web.ApiTimes;
import cn.refinex.meridian.security.apikey.ApiKeyAuthorization;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 待处理动作接口（{@code GET /v1/sessions/{id}/pending-actions}）。 */
@RestController
public class PendingActionsApiController implements PendingActionsApi {

    private final PendingActionQueries queries;

    /**
     * 创建处理器。
     *
     * @param queries 待处理动作查询
     */
    public PendingActionsApiController(PendingActionQueries queries) {
        this.queries = queries;
    }

    @Override
    public Mono<ResponseEntity<PendingActionPage>> listPendingActions(
            String sessionId, Integer limit, String after, PendingActionStatus status, ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        PageRequest page = PageRequest.of(limit, after);
        cn.refinex.meridian.runtime.approval.domain.PendingActionStatus filter = status == null
                ? cn.refinex.meridian.runtime.approval.domain.PendingActionStatus.PENDING
                : cn.refinex.meridian.runtime.approval.domain.PendingActionStatus.of(status.getValue());
        return Blocking.call(() -> toPage(queries.list(sessionId, filter, page)))
                .map(ResponseEntity::ok);
    }

    private static PendingActionPage toPage(PageResult<StoredPendingAction> page) {
        return new PendingActionPage()
                .data(page.data().stream()
                        .map(PendingActionsApiController::toDto)
                        .toList())
                .hasMore(page.hasMore())
                .nextCursor(page.nextCursor());
    }

    private static PendingAction toDto(StoredPendingAction action) {
        return new PendingAction()
                .id(action.id())
                .sessionId(action.sessionId())
                .threadId(action.threadId())
                .turnId(action.turnId())
                .type(PendingActionType.fromValue(action.type().value()))
                .status(PendingActionStatus.fromValue(action.status().value()))
                .toolUseId(action.toolUseId())
                .toolName(action.toolName())
                .input(action.input())
                .expiresAt(ApiTimes.utc(action.expiresAt()))
                .createdAt(ApiTimes.utc(action.createdAt()))
                .resolvedAt(ApiTimes.utc(action.resolvedAt()));
    }
}
