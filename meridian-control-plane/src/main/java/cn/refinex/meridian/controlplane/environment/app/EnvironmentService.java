package cn.refinex.meridian.controlplane.environment.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.environment.domain.Environment;
import cn.refinex.meridian.controlplane.environment.domain.EnvironmentConfig;
import cn.refinex.meridian.controlplane.environment.domain.EnvironmentErrorCode;
import cn.refinex.meridian.controlplane.environment.domain.EnvironmentRevision;
import cn.refinex.meridian.controlplane.environment.infra.EnvironmentRepository;
import cn.refinex.meridian.controlplane.project.app.ProjectService;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 环境的管理用例（架构设计「核心概念」，ADR-0047）：档位、规格或白名单变化时修订号加一并追加修订，只改名字不产生新修订；修改与删除
 * 提交后经 {@code config.invalidate} 通知 runtime，新会话立即使用新修订，运行中的会话继续使用固化的修订。删除是软删除，修订保留。
 */
@Service
public class EnvironmentService {

    private final EnvironmentRepository environments;
    private final ProjectService projects;
    private final AdminAudit audit;
    private final ConfigInvalidations invalidations;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param environments 环境仓储
     * @param projects 项目用例，确认项目存在
     * @param audit 审计
     * @param invalidations 配置变更广播
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public EnvironmentService(
            EnvironmentRepository environments,
            ProjectService projects,
            AdminAudit audit,
            ConfigInvalidations invalidations,
            TransactionTemplate transactions,
            Clock clock) {
        this.environments = environments;
        this.projects = projects;
        this.audit = audit;
        this.invalidations = invalidations;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 创建环境，修订号为 1。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param name 名字
     * @param config 执行配置
     * @return 新环境
     */
    public Environment create(String actor, String tenantId, String projectId, String name, EnvironmentConfig config) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    Instant now = clock.instant();
                    Environment environment = new Environment(
                            Ids.newId(IdPrefix.ENVIRONMENT), tenantId, projectId, name, 1, config, actor, now, now);
                    try {
                        environments.insert(environment);
                    } catch (DuplicateKeyException e) {
                        throw nameTaken(name);
                    }
                    environments.insertRevision(revision(environment, actor, now));
                    audit.record(
                            "environment.created",
                            actor,
                            tenantId,
                            projectId,
                            "environment",
                            environment.id(),
                            details(environment));
                    return environment;
                }));
    }

    /**
     * 读取环境。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id 环境 ID
     * @return 环境
     */
    public Environment get(String tenantId, String projectId, String id) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(Scopes.project(tenantId, projectId), () -> require(id, false));
    }

    /**
     * 列出环境。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param page 分页参数
     * @return 一页环境
     */
    public PageResult<Environment> list(String tenantId, String projectId, PageRequest page) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> PageResult.of(environments.list(page), page, e -> Cursor.of(e.id())));
    }

    /**
     * 整体替换：配置变化时产生新修订。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id 环境 ID
     * @param name 名字
     * @param config 执行配置
     * @return 修改后的环境
     */
    public Environment update(
            String actor, String tenantId, String projectId, String id, String name, EnvironmentConfig config) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    Environment current = require(id, true);
                    Instant now = clock.instant();
                    boolean changed = !current.config().equals(config);
                    long revision = changed ? current.revision() + 1 : current.revision();
                    Environment updated = new Environment(
                            id,
                            tenantId,
                            projectId,
                            name,
                            revision,
                            config,
                            current.createdBy(),
                            current.createdAt(),
                            now);
                    try {
                        environments.update(id, name, revision, now);
                    } catch (DuplicateKeyException e) {
                        throw nameTaken(name);
                    }
                    if (changed) {
                        environments.insertRevision(revision(updated, actor, now));
                        invalidations.resourceChanged(ConfigInvalidations.ENVIRONMENT, tenantId, id);
                    }
                    audit.record(
                            "environment.updated", actor, tenantId, projectId, "environment", id, details(updated));
                    return updated;
                }));
    }

    /**
     * 软删除：不能再用它创建会话，修订保留给运行中的会话。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id 环境 ID
     */
    public void delete(String actor, String tenantId, String projectId, String id) {
        projects.require(tenantId, projectId);
        PlatformContexts.run(
                Scopes.project(tenantId, projectId),
                () -> transactions.executeWithoutResult(status -> {
                    Environment environment = require(id, true);
                    environments.softDelete(id, clock.instant());
                    audit.record(
                            "environment.deleted", actor, tenantId, projectId, "environment", id, details(environment));
                    invalidations.resourceChanged(ConfigInvalidations.ENVIRONMENT, tenantId, id);
                }));
    }

    /**
     * 列出修订历史。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id 环境 ID
     * @param page 分页参数
     * @return 一页修订，按修订号升序
     */
    public PageResult<EnvironmentRevision> revisions(String tenantId, String projectId, String id, PageRequest page) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(Scopes.project(tenantId, projectId), () -> {
            require(id, false);
            return PageResult.of(environments.revisions(id, page), page, r -> Cursor.of(Long.toString(r.revision())));
        });
    }

    /**
     * 读取一个修订。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id 环境 ID
     * @param revision 修订号
     * @return 修订
     */
    public EnvironmentRevision revision(String tenantId, String projectId, String id, long revision) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(Scopes.project(tenantId, projectId), () -> {
            require(id, false);
            return environments
                    .revision(id, revision)
                    .orElseThrow(() -> new NotFoundException(
                            EnvironmentErrorCode.REVISION_NOT_FOUND, "环境 " + id + " 没有修订 " + revision));
        });
    }

    private Environment require(String id, boolean forUpdate) {
        return environments
                .find(id, forUpdate)
                .orElseThrow(() -> new NotFoundException(EnvironmentErrorCode.NOT_FOUND, "环境不存在：" + id));
    }

    private static EnvironmentRevision revision(Environment environment, String actor, Instant now) {
        return new EnvironmentRevision(
                environment.id(),
                environment.revision(),
                environment.tenantId(),
                environment.projectId(),
                environment.config(),
                actor,
                now);
    }

    private static ConflictException nameTaken(String name) {
        return new ConflictException(EnvironmentErrorCode.NAME_TAKEN, "项目内已有名为 " + name + " 的环境");
    }

    private static Map<String, Object> details(Environment environment) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", environment.name());
        details.put("revision", environment.revision());
        details.put("tier", environment.config().tier());
        details.put("size", environment.config().size());
        details.put("egress_allowlist", environment.config().egressAllowlist());
        return details;
    }
}
