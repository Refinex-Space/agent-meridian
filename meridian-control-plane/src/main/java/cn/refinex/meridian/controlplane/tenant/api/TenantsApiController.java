package cn.refinex.meridian.controlplane.tenant.api;

import cn.refinex.meridian.api.admin.model.CreateTenantRequest;
import cn.refinex.meridian.api.admin.model.Tenant;
import cn.refinex.meridian.api.admin.model.TenantPage;
import cn.refinex.meridian.api.admin.model.TenantQuotas;
import cn.refinex.meridian.api.admin.model.TenantSettings;
import cn.refinex.meridian.api.admin.model.UpdateTenantRequest;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.openapi.admin.TenantsApi;
import cn.refinex.meridian.controlplane.tenant.app.TenantService;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 租户的管理接口（{@code meridian-admin-v1.yaml} 的 Tenants 标签）：只做授权声明、decode → call → map。 */
@RestController
public class TenantsApiController implements TenantsApi {

    private final TenantService tenants;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param tenants 租户用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public TenantsApiController(TenantService tenants, AdminRequests requests, HttpServletRequest request) {
        this.tenants = tenants;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.TENANT_CREATE)
    public ResponseEntity<Tenant> createTenant(CreateTenantRequest body, String idempotencyKey) {
        return requests.write(
                request,
                null,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                Tenant.class,
                () -> TenantMapper.toDto(tenants.create(
                        actor(),
                        body.getName(),
                        new TenantService.NewOwner(
                                body.getOwner().getSubject(),
                                body.getOwner().getEmail(),
                                body.getOwner().getDisplayName()),
                        body.getMaxConcurrentTurns(),
                        body.getEventRetentionDays())));
    }

    @Override
    @RequiresPermission(Permission.CONSOLE_ACCESS)
    public ResponseEntity<TenantPage> listTenants(Integer limit, String after) {
        return ResponseEntity.ok(
                TenantMapper.toPage(tenants.list(AccessContext.current(request), PageRequest.of(limit, after))));
    }

    @Override
    @RequiresPermission(Permission.TENANT_READ)
    public ResponseEntity<Tenant> getTenant(String tenantId) {
        return ResponseEntity.ok(TenantMapper.toDto(tenants.get(tenantId)));
    }

    @Override
    @RequiresPermission(Permission.TENANT_UPDATE)
    public ResponseEntity<Tenant> updateTenant(String tenantId, UpdateTenantRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                Tenant.class,
                () -> TenantMapper.toDto(tenants.rename(actor(), tenantId, body.getName())));
    }

    @Override
    @RequiresPermission(Permission.TENANT_DELETE)
    public ResponseEntity<Void> deleteTenant(String tenantId, String idempotencyKey) {
        return requests.delete(request, tenantId, idempotencyKey, () -> tenants.delete(actor(), tenantId));
    }

    @Override
    @RequiresPermission(Permission.TENANT_QUOTA_UPDATE)
    public ResponseEntity<Tenant> updateTenantQuotas(String tenantId, TenantQuotas body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                Tenant.class,
                () -> TenantMapper.toDto(tenants.updateQuotas(
                        actor(), tenantId, body.getMaxConcurrentTurns(), body.getEventRetentionDays())));
    }

    @Override
    @RequiresPermission(Permission.TENANT_READ)
    public ResponseEntity<TenantSettings> getTenantSettings(String tenantId) {
        return ResponseEntity.ok(TenantMapper.toDto(tenants.settings(tenantId)));
    }

    @Override
    @RequiresPermission(Permission.TENANT_UPDATE)
    public ResponseEntity<TenantSettings> updateTenantSettings(
            String tenantId, TenantSettings body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                TenantSettings.class,
                () -> TenantMapper.toDto(tenants.updateSettings(
                        actor(), tenantId, body.getSystemPrompt(), TenantMapper.toDomain(body.getWebSearch()))));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }
}
