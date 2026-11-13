package cn.refinex.meridian.controlplane.secret.api;

import cn.refinex.meridian.api.admin.model.CreateSecretRequest;
import cn.refinex.meridian.api.admin.model.ResourceReference;
import cn.refinex.meridian.api.admin.model.RotateSecretRequest;
import cn.refinex.meridian.api.admin.model.Secret;
import cn.refinex.meridian.api.admin.model.SecretPage;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.openapi.admin.SecretsApi;
import cn.refinex.meridian.controlplane.secret.app.SecretService;
import cn.refinex.meridian.controlplane.secret.app.SecretService.SecretView;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 凭据的管理接口（{@code meridian-admin-v1.yaml} 的 Secrets 标签，ADR-0045）：只做授权声明、decode → call → map。响应只有元数据；
 * 凭据值不进幂等指纹（{@link Fingerprint}），幂等记录里也就没有它的摘要。
 */
@RestController
public class SecretsApiController implements SecretsApi {

    private final SecretService secrets;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param secrets 凭据用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public SecretsApiController(SecretService secrets, AdminRequests requests, HttpServletRequest request) {
        this.secrets = secrets;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.SECRET_WRITE)
    public ResponseEntity<Secret> createSecret(String tenantId, CreateSecretRequest body, String idempotencyKey) {
        return requests.writeSensitive(
                request,
                tenantId,
                idempotencyKey,
                new Fingerprint(body.getName(), body.getProjectId()),
                body.getValue(),
                HttpStatus.CREATED,
                Secret.class,
                () -> toDto(secrets.create(actor(), tenantId, body.getProjectId(), body.getName(), body.getValue())));
    }

    @Override
    @RequiresPermission(Permission.SECRET_READ)
    public ResponseEntity<SecretPage> listSecrets(String tenantId, Integer limit, String after) {
        var page = secrets.list(tenantId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new SecretPage(
                page.data().stream().map(SecretsApiController::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.SECRET_READ)
    public ResponseEntity<Secret> getSecret(String tenantId, String secretId) {
        return ResponseEntity.ok(toDto(secrets.get(tenantId, secretId)));
    }

    @Override
    @RequiresPermission(Permission.SECRET_WRITE)
    public ResponseEntity<Secret> rotateSecret(
            String tenantId, String secretId, RotateSecretRequest body, String idempotencyKey) {
        return requests.writeSensitive(
                request,
                tenantId,
                idempotencyKey,
                null,
                body.getValue(),
                HttpStatus.OK,
                Secret.class,
                () -> toDto(secrets.rotate(actor(), tenantId, secretId, body.getValue())));
    }

    @Override
    @RequiresPermission(Permission.SECRET_WRITE)
    public ResponseEntity<Void> deleteSecret(String tenantId, String secretId, String idempotencyKey) {
        return requests.delete(request, tenantId, idempotencyKey, () -> secrets.delete(actor(), tenantId, secretId));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }

    private static Secret toDto(SecretView view) {
        var secret = view.secret();
        return new Secret(
                secret.id(),
                secret.tenantId(),
                secret.projectId(),
                secret.name(),
                secret.version(),
                secret.createdBy(),
                Times.utc(secret.createdAt()),
                Times.utc(secret.updatedAt()),
                Times.utc(secret.lastUsedAt()),
                view.references().stream()
                        .map(r -> new ResourceReference(r.type(), r.id(), r.name()))
                        .toList());
    }

    /**
     * 写入请求的幂等指纹中的明文部分：名称与项目；凭据值只以带密钥摘要进入指纹（ADR-0045 第 2 条，R2 审查 K-05）。
     *
     * @param name 名称
     * @param projectId 项目
     */
    record Fingerprint(String name, String projectId) {}
}
