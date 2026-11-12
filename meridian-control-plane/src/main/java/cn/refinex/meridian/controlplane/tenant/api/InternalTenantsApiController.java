package cn.refinex.meridian.controlplane.tenant.api;

import cn.refinex.meridian.api.internal.model.InternalTenantSettings;
import cn.refinex.meridian.api.internal.model.InternalWebSearchSettings;
import cn.refinex.meridian.api.internal.model.TenantQuota;
import cn.refinex.meridian.controlplane.openapi.internal.InternalTenantsApi;
import cn.refinex.meridian.controlplane.tenant.app.TenantService;
import cn.refinex.meridian.controlplane.tenant.domain.TenantSettings;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialFilter;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 租户的内部接口：配额（ADR-0042，runtime 为租户创建并发计数行、收到 {@code config.invalidate} 时读取）与租户设置（ADR-0047）。 */
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
        var tenant = tenants.get(tenantId);
        return ResponseEntity.ok(new TenantQuota(tenantId, tenant.maxConcurrentTurns(), tenant.eventRetentionDays()));
    }

    @Override
    public ResponseEntity<InternalTenantSettings> getInternalTenantSettings(String tenantId) {
        ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME);
        TenantSettings settings = tenants.settings(tenantId);
        TenantSettings.WebSearch webSearch = settings.webSearch();
        return ResponseEntity.ok(new InternalTenantSettings()
                .tenantId(tenantId)
                .systemPrompt(settings.systemPrompt())
                .webSearch(
                        webSearch == null
                                ? null
                                : new InternalWebSearchSettings()
                                        .backend(InternalWebSearchSettings.BackendEnum.fromValue(webSearch.backend()))
                                        .baseUrl(webSearch.baseUrl())
                                        .secretId(webSearch.secretId())));
    }
}
