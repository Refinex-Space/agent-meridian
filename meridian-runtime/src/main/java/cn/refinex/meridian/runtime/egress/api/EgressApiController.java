package cn.refinex.meridian.runtime.egress.api;

import cn.refinex.meridian.api.internal.model.EgressAuthorization;
import cn.refinex.meridian.api.internal.model.EgressAuthorizeRequest;
import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.runtime.egress.app.EgressAuthorizer;
import cn.refinex.meridian.runtime.egress.domain.EgressDecision;
import cn.refinex.meridian.runtime.egress.domain.EgressErrorCode;
import cn.refinex.meridian.runtime.openapi.internal.InternalEgressApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 出网代理的授权回调 {@code POST /internal/v1/egress:authorize}（架构设计「沙箱出网」，ADR-0038）：放行返回 200 与会话 ID，拒绝以
 * {@code egress.*} 的 403 结束（problem+json）。服务凭据由 {@code ServiceCredentialWebFilter} 在进入处理器之前校验。
 */
@RestController
public class EgressApiController implements InternalEgressApi {

    private final EgressAuthorizer authorizer;

    /**
     * 创建处理器。
     *
     * @param authorizer 授权判定
     */
    public EgressApiController(EgressAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public Mono<ResponseEntity<EgressAuthorization>> authorizeEgress(
            Mono<EgressAuthorizeRequest> egressAuthorizeRequest, ServerWebExchange exchange) {
        return egressAuthorizeRequest
                .flatMap(request ->
                        Blocking.call(() -> authorizer.authorize(request.getSourceIp(), request.getTarget())))
                .map(EgressApiController::toResponse);
    }

    private static ResponseEntity<EgressAuthorization> toResponse(EgressDecision decision) {
        if (!decision.allowed()) {
            throw new MeridianException(EgressErrorCode.of(decision.reason()), "拒绝沙箱出网");
        }
        return ResponseEntity.ok(new EgressAuthorization(decision.sessionId()));
    }
}
