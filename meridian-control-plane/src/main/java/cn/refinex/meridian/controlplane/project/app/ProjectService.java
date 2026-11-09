package cn.refinex.meridian.controlplane.project.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.project.domain.Project;
import cn.refinex.meridian.controlplane.project.domain.ProjectDeleted;
import cn.refinex.meridian.controlplane.project.domain.ProjectErrorCode;
import cn.refinex.meridian.controlplane.project.infra.ProjectRepository;
import cn.refinex.meridian.controlplane.tenant.domain.TenantDeleted;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 项目的用例：增删改查（架构设计「租户层级」）。删除是软删除，在同一事务中发布 {@link ProjectDeleted}，其下的 API Key 与项目级角色
 * 绑定随之处理；租户删除时删除其全部项目。
 */
@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final AdminAudit audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param projects 项目仓储
     * @param audit 审计
     * @param events 进程内事件（同一事务中同步处理）
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public ProjectService(
            ProjectRepository projects,
            AdminAudit audit,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            Clock clock) {
        this.projects = projects;
        this.audit = audit;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 创建项目。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param name 名称
     * @param description 说明；可为空
     * @return 新项目
     */
    public Project create(String actor, String tenantId, String name, String description) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    Instant now = clock.instant();
                    Project project = new Project(
                            Ids.newId(IdPrefix.PROJECT),
                            tenantId,
                            name,
                            description == null ? "" : description,
                            now,
                            now);
                    projects.insert(project);
                    audit.record(
                            "project.created",
                            actor,
                            tenantId,
                            project.id(),
                            "project",
                            project.id(),
                            Map.of("name", name));
                    return project;
                }));
    }

    /**
     * 读取项目。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @return 项目
     * @throws NotFoundException 不存在、已删除或不属于该租户时
     */
    public Project require(String tenantId, String projectId) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId), () -> projects.find(projectId).orElseThrow(() -> notFound(projectId)));
    }

    /**
     * 列出项目。
     *
     * @param tenantId 租户 ID
     * @param page 分页参数
     * @return 一页项目
     */
    public PageResult<Project> list(String tenantId, PageRequest page) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId), () -> PageResult.of(projects.list(page), page, p -> Cursor.of(p.id())));
    }

    /**
     * 修改项目，只修改给出的字段。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param name 新名称；可为空，表示不变
     * @param description 新说明；可为空，表示不变
     * @return 修改后的项目
     */
    public Project update(String actor, String tenantId, String projectId, String name, String description) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    Project current = projects.find(projectId).orElseThrow(() -> notFound(projectId));
                    Project updated = new Project(
                            current.id(),
                            tenantId,
                            name == null ? current.name() : name,
                            description == null ? current.description() : description,
                            current.createdAt(),
                            clock.instant());
                    projects.update(updated);
                    audit.record(
                            "project.updated",
                            actor,
                            tenantId,
                            projectId,
                            "project",
                            projectId,
                            Map.of("name", updated.name()));
                    return updated;
                }));
    }

    /**
     * 删除项目：标记删除，并在同一事务中吊销其 API Key、删除项目级角色绑定。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     */
    public void delete(String actor, String tenantId, String projectId) {
        PlatformContexts.run(
                Scopes.tenant(tenantId),
                () -> transactions.executeWithoutResult(status -> {
                    projects.find(projectId).orElseThrow(() -> notFound(projectId));
                    projects.markDeleted(projectId, clock.instant());
                    deleted(actor, tenantId, projectId);
                }));
    }

    /**
     * 租户删除时删除它的全部项目（与删除租户同一事务）。
     *
     * @param event 租户删除事件
     */
    @EventListener
    public void onTenantDeleted(TenantDeleted event) {
        PlatformContexts.run(
                Scopes.tenant(event.tenantId()),
                () -> projects.markAllDeleted(clock.instant())
                        .forEach(projectId -> deleted(event.actor(), event.tenantId(), projectId)));
    }

    private void deleted(String actor, String tenantId, String projectId) {
        audit.record("project.deleted", actor, tenantId, projectId, "project", projectId, Map.of());
        events.publishEvent(new ProjectDeleted(tenantId, projectId, actor));
    }

    private static NotFoundException notFound(String projectId) {
        return new NotFoundException(ProjectErrorCode.NOT_FOUND, "项目不存在：" + projectId);
    }
}
