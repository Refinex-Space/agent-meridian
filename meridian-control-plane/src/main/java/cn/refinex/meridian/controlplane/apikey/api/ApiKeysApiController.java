package cn.refinex.meridian.controlplane.apikey.api;

import cn.refinex.meridian.api.admin.model.ApiKey;
import cn.refinex.meridian.api.admin.model.ApiKeyPage;
import cn.refinex.meridian.api.admin.model.CreateApiKeyRequest;
import cn.refinex.meridian.api.admin.model.CreatedApiKey;
import cn.refinex.meridian.api.admin.model.UpdateApiKeyRequest;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.apikey.app.ApiKeyService;
import cn.refinex.meridian.controlplane.openapi.admin.ApiKeysApi;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** API Key 的管理接口（{@code meridian-admin-v1.yaml} 的 ApiKeys 标签）：只做授权声明、decode → call → map。 */
@RestController
public class ApiKeysApiController implements ApiKeysApi {

    private final ApiKeyService keys;
    private final AdminRequests requests;
    private final HttpServletRequest request;
    private final Clock clock;

    /**
     * 创建处理器。
     *
     * @param keys API Key 用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     * @param clock 时钟，计算状态
     */
    public ApiKeysApiController(ApiKeyService keys, AdminRequests requests, HttpServletRequest request, Clock clock) {
        this.keys = keys;
        this.requests = requests;
        this.request = request;
        this.clock = clock;
    }

    @Override
    @RequiresPermission(Permission.API_KEY_WRITE)
    public ResponseEntity<CreatedApiKey> createApiKey(
            String tenantId, String projectId, CreateApiKeyRequest body, String idempotencyKey) {
        Set<ApiKeyScope> scopes = EnumSet.noneOf(ApiKeyScope.class);
        body.getScopes()
                .forEach(scope ->
                        scopes.add(ApiKeyScope.fromValue(scope.getValue()).orElseThrow()));
        return requests.write(request, tenantId, idempotencyKey, body, HttpStatus.CREATED, CreatedApiKey.class, () -> {
            ApiKeyService.Created created = keys.create(
                    actor(),
                    tenantId,
                    projectId,
                    body.getName(),
                    scopes,
                    Times.instant(body.getExpiresAt()),
                    body.getRateLimitPerMinute());
            ApiKey dto = toDto(created.key());
            return new CreatedApiKey(
                    dto.getId(),
                    dto.getTenantId(),
                    dto.getProjectId(),
                    dto.getName(),
                    dto.getDisplayPrefix(),
                    dto.getScopes(),
                    dto.getRateLimitPerMinute(),
                    CreatedApiKey.StatusEnum.fromValue(dto.getStatus().getValue()),
                    dto.getExpiresAt(),
                    dto.getCreatedAt(),
                    dto.getRevokedAt(),
                    created.secret().plaintext());
        });
    }

    @Override
    @RequiresPermission(Permission.API_KEY_READ)
    public ResponseEntity<ApiKeyPage> listApiKeys(String tenantId, String projectId, Integer limit, String after) {
        var page = keys.list(tenantId, projectId, PageRequest.of(limit, after));
        return ResponseEntity.ok(
                new ApiKeyPage(page.data().stream().map(this::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.API_KEY_READ)
    public ResponseEntity<ApiKey> getApiKey(String tenantId, String projectId, String apiKeyId) {
        return ResponseEntity.ok(toDto(keys.get(tenantId, projectId, apiKeyId)));
    }

    @Override
    @RequiresPermission(Permission.API_KEY_WRITE)
    public ResponseEntity<ApiKey> updateApiKey(
            String tenantId, String projectId, String apiKeyId, UpdateApiKeyRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                ApiKey.class,
                () -> toDto(keys.rename(actor(), tenantId, projectId, apiKeyId, body.getName())));
    }

    @Override
    @RequiresPermission(Permission.API_KEY_WRITE)
    public ResponseEntity<ApiKey> revokeApiKey(
            String tenantId, String projectId, String apiKeyId, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                null,
                HttpStatus.OK,
                ApiKey.class,
                () -> toDto(keys.revoke(actor(), tenantId, projectId, apiKeyId)));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }

    private ApiKey toDto(cn.refinex.meridian.controlplane.apikey.domain.ApiKey key) {
        return new ApiKey(
                key.id(),
                key.tenantId(),
                key.projectId(),
                key.name(),
                key.displayPrefix(),
                key.scopes().stream()
                        .map(ApiKeyScope::value)
                        .sorted()
                        .map(cn.refinex.meridian.api.admin.model.ApiKeyScope::fromValue)
                        .toList(),
                key.rateLimitPerMinute(),
                ApiKey.StatusEnum.fromValue(key.status(clock.instant()).value()),
                Times.utc(key.expiresAt()),
                Times.utc(key.createdAt()),
                Times.utc(key.revokedAt()));
    }
}
