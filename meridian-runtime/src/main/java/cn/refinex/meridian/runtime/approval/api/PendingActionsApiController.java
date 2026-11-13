package cn.refinex.meridian.runtime.approval.api;

import cn.refinex.meridian.api.model.PendingAction;
import cn.refinex.meridian.api.model.PendingActionConclusion;
import cn.refinex.meridian.api.model.PendingActionPage;
import cn.refinex.meridian.api.model.PendingActionStatus;
import cn.refinex.meridian.api.model.PendingActionType;
import cn.refinex.meridian.api.model.RecoveryDecision;
import cn.refinex.meridian.api.model.RememberScope;
import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.runtime.approval.app.PendingActionQueries;
import cn.refinex.meridian.runtime.approval.domain.ActionConclusion;
import cn.refinex.meridian.runtime.approval.domain.PendingActionFilter;
import cn.refinex.meridian.runtime.approval.domain.PendingActionView;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import cn.refinex.meridian.runtime.openapi.PendingActionsApi;
import cn.refinex.meridian.runtime.web.ApiTimes;
import cn.refinex.meridian.security.apikey.ApiKeyAuthorization;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 待处理动作接口：会话内的 {@code GET /v1/sessions/{id}/pending-actions} 与项目级的 {@code GET /v1/pending-actions}（ADR-0053）。 */
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
                : statusOf(status);
        return Blocking.call(() -> toPage(queries.list(sessionId, filter, page)))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<PendingActionPage>> listProjectPendingActions(
            Integer limit,
            String after,
            PendingActionStatus status,
            PendingActionType type,
            String agentId,
            String sessionId,
            ServerWebExchange exchange) {
        ApiKeyAuthorization.require(exchange, ApiKeyScope.SESSIONS_READ);
        PageRequest page = PageRequest.of(limit, after);
        PendingActionFilter filter = new PendingActionFilter(
                status == null ? null : statusOf(status), type == null ? null : typeOf(type), agentId, sessionId);
        return Blocking.call(() -> toPage(queries.listProject(filter, page))).map(ResponseEntity::ok);
    }

    private static cn.refinex.meridian.runtime.approval.domain.PendingActionStatus statusOf(
            PendingActionStatus status) {
        return cn.refinex.meridian.runtime.approval.domain.PendingActionStatus.of(status.getValue());
    }

    private static cn.refinex.meridian.engine.spi.event.PendingAction.Type typeOf(PendingActionType type) {
        return Arrays.stream(cn.refinex.meridian.engine.spi.event.PendingAction.Type.values())
                .filter(t -> t.value().equals(type.getValue()))
                .findFirst()
                .orElseThrow();
    }

    private static PendingActionPage toPage(PageResult<PendingActionView> page) {
        return new PendingActionPage()
                .data(page.data().stream()
                        .map(PendingActionsApiController::toDto)
                        .toList())
                .hasMore(page.hasMore())
                .nextCursor(page.nextCursor());
    }

    private static PendingAction toDto(PendingActionView view) {
        StoredPendingAction action = view.action();
        return new PendingAction()
                .id(action.id())
                .sessionId(action.sessionId())
                .agentId(view.agentId())
                .threadId(action.threadId())
                .turnId(action.turnId())
                .type(PendingActionType.fromValue(action.type().value()))
                .status(PendingActionStatus.fromValue(action.status().value()))
                .toolUseId(action.toolUseId())
                .toolName(action.toolName())
                .input(action.input())
                .suggestedRules(action.suggestedRules())
                .expiresAt(ApiTimes.utc(action.expiresAt()))
                .createdAt(ApiTimes.utc(action.createdAt()))
                .resolvedAt(ApiTimes.utc(action.resolvedAt()))
                .decidedBy(view.decidedBy())
                .conclusion(view.conclusion() == null ? null : toDto(view.conclusion()));
    }

    private static PendingActionConclusion toDto(ActionConclusion conclusion) {
        return new PendingActionConclusion()
                .origin(PendingActionConclusion.OriginEnum.fromValue(
                        conclusion.origin().name().toLowerCase(Locale.ROOT)))
                .approved(conclusion.approved())
                .denyReason(conclusion.denyReason())
                .rememberScope(
                        conclusion.remember() == null
                                ? null
                                : RememberScope.fromValue(conclusion.remember().value()))
                .decision(
                        conclusion.decision() == null
                                ? null
                                : RecoveryDecision.fromValue(
                                        conclusion.decision().value()))
                .error(conclusion.error())
                .rememberRule(conclusion.rememberRule());
    }
}
