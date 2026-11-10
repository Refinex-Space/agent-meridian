package cn.refinex.meridian.controlplane.apikey.api;

import cn.refinex.meridian.api.internal.model.ApiKeyVerification;
import cn.refinex.meridian.api.internal.model.ApiKeyVerifyRequest;
import cn.refinex.meridian.controlplane.apikey.app.ApiKeyVerifier;
import cn.refinex.meridian.controlplane.apikey.domain.ApiKey;
import cn.refinex.meridian.controlplane.openapi.internal.InternalApiKeysApi;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialFilter;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** API Key 校验的内部接口（ADR-0042）：runtime 与 knowledge 的 {@code ControlPlaneApiKeyResolver} 按哈希调用。 */
@RestController
public class InternalApiKeysApiController implements InternalApiKeysApi {

    private final ApiKeyVerifier verifier;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param verifier 校验用例
     * @param request 当前请求（Spring 注入的代理）
     */
    public InternalApiKeysApiController(ApiKeyVerifier verifier, HttpServletRequest request) {
        this.verifier = verifier;
        this.request = request;
    }

    @Override
    public ResponseEntity<ApiKeyVerification> verifyApiKey(ApiKeyVerifyRequest body) {
        ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME, ServiceJwts.KNOWLEDGE);
        ApiKey key = verifier.verify(body.getKeyHash());
        return ResponseEntity.ok(new ApiKeyVerification(
                key.id(),
                key.tenantId(),
                key.projectId(),
                key.scopes().stream().map(ApiKeyScope::value).sorted().toList(),
                key.rateLimitPerMinute(),
                Times.utc(key.expiresAt())));
    }
}
