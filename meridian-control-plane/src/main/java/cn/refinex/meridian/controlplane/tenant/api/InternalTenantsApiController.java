package cn.refinex.meridian.controlplane.tenant.api;

import cn.refinex.meridian.api.internal.model.TenantQuota;
import cn.refinex.meridian.controlplane.openapi.internal.InternalTenantsApi;
import cn.refinex.meridian.controlplane.tenant.app.TenantService;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialFilter;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 租户配额的内部接口（ADR-0042）：runtime 为租户创建并发计数行、收到 {@code config.invalidate} 时读取。 */
@RestController
public class InternalTenantsApiController implements InternalTenantsApi {

    private final TenantService tenants;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param tenants 租户用例
     * @param request 当前请求（Spring 注入的代理）
     */
    public InternalTenantsApiController(TenantService tenants, HttpServletRequest request) {
        this.tenants = tenants;
        this.request = request;
    }

    @Override
    public ResponseEntity<TenantQuota> getTenantQuotas(String tenantId) {
        ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME, ServiceJwts.KNOWLEDGE);
        return ResponseEntity.ok(new TenantQuota(tenantId, tenants.get(tenantId).maxConcurrentTurns()));
    }
}
