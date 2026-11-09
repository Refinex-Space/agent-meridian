package cn.refinex.meridian.controlplane.project.api;

import cn.refinex.meridian.api.admin.model.CreateProjectRequest;
import cn.refinex.meridian.api.admin.model.Project;
import cn.refinex.meridian.api.admin.model.ProjectPage;
import cn.refinex.meridian.api.admin.model.UpdateProjectRequest;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.openapi.admin.ProjectsApi;
import cn.refinex.meridian.controlplane.project.app.ProjectService;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 项目的管理接口（{@code meridian-admin-v1.yaml} 的 Projects 标签）：只做授权声明、decode → call → map。 */
@RestController
public class ProjectsApiController implements ProjectsApi {

    private final ProjectService projects;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param projects 项目用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public ProjectsApiController(ProjectService projects, AdminRequests requests, HttpServletRequest request) {
        this.projects = projects;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.PROJECT_CREATE)
    public ResponseEntity<Project> createProject(String tenantId, CreateProjectRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                Project.class,
                () -> toDto(projects.create(actor(), tenantId, body.getName(), body.getDescription())));
    }

    @Override
    @RequiresPermission(Permission.TENANT_READ)
    public ResponseEntity<ProjectPage> listProjects(String tenantId, Integer limit, String after) {
        var page = projects.list(tenantId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new ProjectPage(
                page.data().stream().map(ProjectsApiController::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.PROJECT_READ)
    public ResponseEntity<Project> getProject(String tenantId, String projectId) {
        return ResponseEntity.ok(toDto(projects.require(tenantId, projectId)));
    }

    @Override
    @RequiresPermission(Permission.PROJECT_UPDATE)
    public ResponseEntity<Project> updateProject(
            String tenantId, String projectId, UpdateProjectRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                Project.class,
                () -> toDto(projects.update(actor(), tenantId, projectId, body.getName(), body.getDescription())));
    }

    @Override
    @RequiresPermission(Permission.PROJECT_DELETE)
    public ResponseEntity<Void> deleteProject(String tenantId, String projectId, String idempotencyKey) {
        return requests.delete(request, tenantId, idempotencyKey, () -> projects.delete(actor(), tenantId, projectId));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }

    private static Project toDto(cn.refinex.meridian.controlplane.project.domain.Project project) {
        return new Project(
                project.id(),
                project.tenantId(),
                project.name(),
                project.description(),
                Times.utc(project.createdAt()),
                Times.utc(project.updatedAt()));
    }
}
