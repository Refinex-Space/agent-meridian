package cn.refinex.meridian.controlplane.catalog.api;

import cn.refinex.meridian.api.admin.model.CreateProviderAccountRequest;
import cn.refinex.meridian.api.admin.model.ProviderAccount;
import cn.refinex.meridian.api.admin.model.ProviderAccountPage;
import cn.refinex.meridian.api.admin.model.UpdateProviderAccountRequest;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.catalog.app.ProviderAccountService;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderType;
import cn.refinex.meridian.controlplane.openapi.admin.ProviderAccountsApi;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 供应商账号的管理接口（ProviderAccounts 标签，ADR-0045）：只做授权声明、decode → call → map。 */
@RestController
public class ProviderAccountsApiController implements ProviderAccountsApi {

    private final ProviderAccountService accounts;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param accounts 供应商账号用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public ProviderAccountsApiController(
            ProviderAccountService accounts, AdminRequests requests, HttpServletRequest request) {
        this.accounts = accounts;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.MODEL_WRITE)
    public ResponseEntity<ProviderAccount> createProviderAccount(
            String tenantId, CreateProviderAccountRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                ProviderAccount.class,
                () -> CatalogMapper.toDto(accounts.create(
                        actor(),
                        tenantId,
                        body.getName(),
                        ProviderType.fromValue(body.getProvider().getValue()).orElseThrow(),
                        body.getBaseUrl(),
                        body.getSecretId(),
                        CatalogMapper.fromDto(body.getRateLimit()))));
    }

    @Override
    @RequiresPermission(Permission.MODEL_READ)
    public ResponseEntity<ProviderAccountPage> listProviderAccounts(String tenantId, Integer limit, String after) {
        var page = accounts.list(tenantId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new ProviderAccountPage(
                page.data().stream().map(CatalogMapper::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.MODEL_READ)
    public ResponseEntity<ProviderAccount> getProviderAccount(String tenantId, String providerAccountId) {
        return ResponseEntity.ok(CatalogMapper.toDto(accounts.get(tenantId, providerAccountId)));
    }

    @Override
    @RequiresPermission(Permission.MODEL_WRITE)
    public ResponseEntity<ProviderAccount> updateProviderAccount(
            String tenantId, String providerAccountId, UpdateProviderAccountRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                ProviderAccount.class,
                () -> CatalogMapper.toDto(accounts.update(
                        actor(),
                        tenantId,
                        providerAccountId,
                        body.getName(),
                        body.getBaseUrl(),
                        body.getSecretId(),
                        CatalogMapper.fromDto(body.getRateLimit()))));
    }

    @Override
    @RequiresPermission(Permission.MODEL_WRITE)
    public ResponseEntity<Void> deleteProviderAccount(
            String tenantId, String providerAccountId, String idempotencyKey) {
        return requests.delete(
                request, tenantId, idempotencyKey, () -> accounts.delete(actor(), tenantId, providerAccountId));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }
}
