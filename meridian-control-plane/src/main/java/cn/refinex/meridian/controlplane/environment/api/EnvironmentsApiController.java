package cn.refinex.meridian.controlplane.environment.api;

import cn.refinex.meridian.api.admin.model.Environment;
import cn.refinex.meridian.api.admin.model.EnvironmentPage;
import cn.refinex.meridian.api.admin.model.EnvironmentRequest;
import cn.refinex.meridian.api.admin.model.EnvironmentRevision;
import cn.refinex.meridian.api.admin.model.EnvironmentRevisionPage;
import cn.refinex.meridian.api.admin.model.EnvironmentTier;
import cn.refinex.meridian.api.admin.model.SandboxSize;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.environment.app.EnvironmentService;
import cn.refinex.meridian.controlplane.environment.domain.EnvironmentConfig;
import cn.refinex.meridian.controlplane.openapi.admin.EnvironmentsApi;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 环境的管理接口（Environments 标签，ADR-0047）：只做授权声明、decode → call → map。 */
@RestController
public class EnvironmentsApiController implements EnvironmentsApi {

    private final EnvironmentService environments;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param environments 环境用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public EnvironmentsApiController(
            EnvironmentService environments, AdminRequests requests, HttpServletRequest request) {
        this.environments = environments;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<Environment> createEnvironment(
            String tenantId, String projectId, EnvironmentRequest body, String idempotencyKey) {
        EnvironmentConfig config = config(body);
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                Environment.class,
                () -> toDto(environments.create(actor(), tenantId, projectId, body.getName(), config)));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<EnvironmentPage> listEnvironments(
            String tenantId, String projectId, Integer limit, String after) {
        var page = environments.list(tenantId, projectId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new EnvironmentPage(
                page.data().stream().map(EnvironmentsApiController::toDto).toList(),
                page.hasMore(),
                page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<Environment> getEnvironment(String tenantId, String projectId, String environmentId) {
        return ResponseEntity.ok(toDto(environments.get(tenantId, projectId, environmentId)));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<Environment> updateEnvironment(
            String tenantId, String projectId, String environmentId, EnvironmentRequest body, String idempotencyKey) {
        EnvironmentConfig config = config(body);
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                Environment.class,
                () -> toDto(environments.update(actor(), tenantId, projectId, environmentId, body.getName(), config)));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<Void> deleteEnvironment(
            String tenantId, String projectId, String environmentId, String idempotencyKey) {
        return requests.delete(
                request,
                tenantId,
                idempotencyKey,
                () -> environments.delete(actor(), tenantId, projectId, environmentId));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<EnvironmentRevisionPage> listEnvironmentRevisions(
            String tenantId, String projectId, String environmentId, Integer limit, String after) {
        var page = environments.revisions(tenantId, projectId, environmentId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new EnvironmentRevisionPage(
                page.data().stream().map(EnvironmentsApiController::toDto).toList(),
                page.hasMore(),
                page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<EnvironmentRevision> getEnvironmentRevision(
            String tenantId, String projectId, String environmentId, Long revision) {
        return ResponseEntity.ok(toDto(environments.revision(tenantId, projectId, environmentId, revision)));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }

    private static EnvironmentConfig config(EnvironmentRequest body) {
        return new EnvironmentConfig(
                body.getTier().getValue(),
                body.getSize() == null ? null : body.getSize().getValue(),
                body.getEgressAllowlist());
    }

    static Environment toDto(cn.refinex.meridian.controlplane.environment.domain.Environment environment) {
        return new Environment()
                .id(environment.id())
                .tenantId(environment.tenantId())
                .projectId(environment.projectId())
                .name(environment.name())
                .revision(environment.revision())
                .tier(EnvironmentTier.fromValue(environment.config().tier()))
                .size(size(environment.config()))
                .egressAllowlist(environment.config().egressAllowlist())
                .createdAt(Times.utc(environment.createdAt()))
                .updatedAt(Times.utc(environment.updatedAt()));
    }

    static EnvironmentRevision toDto(cn.refinex.meridian.controlplane.environment.domain.EnvironmentRevision revision) {
        return new EnvironmentRevision()
                .environmentId(revision.environmentId())
                .revision(revision.revision())
                .tier(EnvironmentTier.fromValue(revision.config().tier()))
                .size(size(revision.config()))
                .egressAllowlist(revision.config().egressAllowlist())
                .createdBy(revision.createdBy())
                .createdAt(Times.utc(revision.createdAt()));
    }

    private static SandboxSize size(EnvironmentConfig config) {
        return config.size() == null ? null : SandboxSize.fromValue(config.size());
    }
}
