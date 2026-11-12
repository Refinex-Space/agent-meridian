package cn.refinex.meridian.controlplane.secret.api;

import cn.refinex.meridian.api.internal.model.ResolvedSecret;
import cn.refinex.meridian.api.internal.model.SecretResolveRequest;
import cn.refinex.meridian.controlplane.openapi.internal.InternalSecretsApi;
import cn.refinex.meridian.controlplane.secret.app.SecretResolution;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialFilter;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 凭据解析的内部接口（ADR-0045 第 3 条）：只接受 runtime 的服务 JWT，响应不可缓存。 */
@RestController
public class InternalSecretsApiController implements InternalSecretsApi {

    private final SecretResolution resolution;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param resolution 解析用例
     * @param request 当前请求（Spring 注入的代理）
     */
    public InternalSecretsApiController(SecretResolution resolution, HttpServletRequest request) {
        this.resolution = resolution;
        this.request = request;
    }

    @Override
    public ResponseEntity<ResolvedSecret> resolveSecret(SecretResolveRequest body) {
        String caller =
                ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME).service();
        SecretResolution.Resolved resolved =
                resolution.resolve(caller, body.getTenantId(), body.getProjectId(), body.getSecretId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ResolvedSecret(resolved.secretId(), resolved.version(), resolved.value()));
    }
}
