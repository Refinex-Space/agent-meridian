package cn.refinex.meridian.controlplane.agent.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.agent.domain.Agent;
import cn.refinex.meridian.controlplane.agent.domain.AgentErrorCode;
import cn.refinex.meridian.controlplane.agent.domain.AgentVersion;
import cn.refinex.meridian.controlplane.agent.domain.SpecChange;
import cn.refinex.meridian.controlplane.agent.domain.SpecDiff;
import cn.refinex.meridian.controlplane.agent.infra.AgentRepository;
import cn.refinex.meridian.controlplane.agent.infra.AgentVersionRefRepository;
import cn.refinex.meridian.controlplane.agent.infra.AgentVersionRepository;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.project.app.ProjectService;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 智能体版本的用例（架构设计「核心概念」，ADR-0047）：草稿的创建、修改与删除（规格按 JSON Schema 校验结构）；发布（{@link AgentPublication}
 * 的六项校验与快照，写入引用，从此只读，提交后经 {@code config.invalidate} 让新会话立即使用它）；版本对比。同一智能体的建版本与发布锁住
 * 智能体行串行执行。
 */
@Service
public class AgentVersionService {

    private final AgentRepository agents;
    private final AgentVersionRepository versions;
    private final AgentVersionRefRepository references;
    private final AgentSpecSchema schema;
    private final AgentPublication publication;
    private final ProjectService projects;
    private final AdminAudit audit;
    private final ConfigInvalidations invalidations;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param agents 智能体仓储
     * @param versions 版本仓储
     * @param references 已发布版本的引用
     * @param schema 规格的 JSON Schema
     * @param publication 发布前的校验与快照
     * @param projects 项目用例，确认项目存在
     * @param audit 审计
     * @param invalidations 配置变更广播
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public AgentVersionService(
            AgentRepository agents,
            AgentVersionRepository versions,
            AgentVersionRefRepository references,
            AgentSpecSchema schema,
            AgentPublication publication,
            ProjectService projects,
            AdminAudit audit,
            ConfigInvalidations invalidations,
            TransactionTemplate transactions,
            Clock clock) {
        this.agents = agents;
        this.versions = versions;
        this.references = references;
        this.schema = schema;
        this.publication = publication;
        this.projects = projects;
        this.audit = audit;
        this.invalidations = invalidations;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 创建草稿，版本号按智能体递增。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param agentId 智能体 ID
     * @param spec 规格（请求体中的 JSON 对象）
     * @return 新草稿
     */
    public AgentVersion createDraft(
            String actor, String tenantId, String projectId, String agentId, Map<String, Object> spec) {
        String json = validDraft(spec);
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    requireAgent(agentId, true);
                    Instant now = clock.instant();
                    AgentVersion version = new AgentVersion(
                            Ids.newId(IdPrefix.AGENT_VERSION),
                            tenantId,
                            projectId,
                            agentId,
                            versions.nextVersion(agentId),
                            AgentVersion.Status.DRAFT,
                            json,
                            actor,
                            now,
                            now,
                            null,
                            null);
                    versions.insert(version);
                    audit.record(
                            "agent_version.created",
                            actor,
                            tenantId,
                            projectId,
                            "agent_version",
                            version.id(),
                            Map.of("agent_id", agentId, "version", version.version()));
                    return version;
                }));
    }

    /**
     * 整体替换草稿的规格；已发布的版本以 {@code agent.version_published} 拒绝。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param agentId 智能体 ID
     * @param version 版本号
     * @param spec 新规格
     * @return 修改后的草稿
     */
    public AgentVersion updateDraft(
            String actor, String tenantId, String projectId, String agentId, int version, Map<String, Object> spec) {
        String json = validDraft(spec);
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    requireAgent(agentId, false);
                    AgentVersion current = requireVersion(agentId, version, true);
                    if (current.published() || !versions.updateDraft(current.id(), json, clock.instant())) {
                        throw published(current);
                    }
                    audit.record(
                            "agent_version.updated",
                            actor,
                            tenantId,
                            projectId,
                            "agent_version",
                            current.id(),
                            Map.of("agent_id", agentId, "version", version));
                    return requireVersion(agentId, version, false);
                }));
    }

    /**
     * 删除草稿；已发布的版本以 {@code agent.version_published} 拒绝。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param agentId 智能体 ID
     * @param version 版本号
     */
    public void deleteDraft(String actor, String tenantId, String projectId, String agentId, int version) {
        projects.require(tenantId, projectId);
        PlatformContexts.run(
                Scopes.project(tenantId, projectId),
                () -> transactions.executeWithoutResult(status -> {
                    requireAgent(agentId, false);
                    AgentVersion current = requireVersion(agentId, version, true);
                    if (current.published() || !versions.deleteDraft(current.id())) {
                        throw published(current);
                    }
                    audit.record(
                            "agent_version.deleted",
                            actor,
                            tenantId,
                            projectId,
                            "agent_version",
                            current.id(),
                            Map.of("agent_id", agentId, "version", version));
                }));
    }

    /**
     * 发布草稿：校验、写入快照与引用，从此不可变；该智能体的新会话立即使用它。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param agentId 智能体 ID
     * @param version 版本号
     * @return 已发布的版本
     */
    public AgentVersion publish(String actor, String tenantId, String projectId, String agentId, int version) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    Agent agent = requireAgent(agentId, true);
                    AgentVersion draft = requireVersion(agentId, version, true);
                    if (draft.published()) {
                        throw published(draft);
                    }
                    AgentPublication.Prepared prepared = publication.prepare(agent, draft);
                    if (!versions.publish(draft.id(), prepared.spec(), actor, clock.instant())) {
                        throw published(draft);
                    }
                    PlatformContexts.run(
                            Scopes.tenant(tenantId), () -> references.insert(draft.id(), prepared.references()));
                    audit.record(
                            "agent_version.published",
                            actor,
                            tenantId,
                            projectId,
                            "agent_version",
                            draft.id(),
                            Map.of("agent_id", agentId, "version", version));
                    invalidations.resourceChanged(ConfigInvalidations.AGENT, tenantId, agentId);
                    return requireVersion(agentId, version, false);
                }));
    }

    /**
     * 读取一个版本。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param agentId 智能体 ID
     * @param version 版本号
     * @return 版本
     */
    public AgentVersion get(String tenantId, String projectId, String agentId, int version) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(Scopes.project(tenantId, projectId), () -> {
            requireAgent(agentId, false);
            return requireVersion(agentId, version, false);
        });
    }

    /**
     * 列出版本（含草稿）。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param agentId 智能体 ID
     * @param page 分页参数
     * @return 一页版本，按版本号升序
     */
    public PageResult<AgentVersion> list(String tenantId, String projectId, String agentId, PageRequest page) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(Scopes.project(tenantId, projectId), () -> {
            requireAgent(agentId, false);
            return PageResult.of(versions.list(agentId, page), page, v -> Cursor.of(Integer.toString(v.version())));
        });
    }

    /**
     * 对比两个版本的规格。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param agentId 智能体 ID
     * @param base 基准版本号
     * @param target 目标版本号
     * @return 目标相对基准的差异
     */
    public List<SpecChange> compare(String tenantId, String projectId, String agentId, int base, int target) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(Scopes.project(tenantId, projectId), () -> {
            requireAgent(agentId, false);
            AgentVersion from = requireVersion(agentId, base, false);
            AgentVersion to = requireVersion(agentId, target, false);
            return SpecDiff.compare(
                    JsonMappers.lenient().readTree(from.spec()),
                    JsonMappers.lenient().readTree(to.spec()));
        });
    }

    /** 草稿的规格按 JSON Schema 校验结构，返回 JSON 文本。 */
    private String validDraft(Map<String, Object> spec) {
        String json = JsonMappers.lenient().writeValueAsString(spec);
        var violations = schema.validate(json);
        if (!violations.isEmpty()) {
            throw new ValidationException(CommonErrorCode.VALIDATION_FAILED, "智能体规格不合法", violations);
        }
        return json;
    }

    private Agent requireAgent(String agentId, boolean forUpdate) {
        return agents.find(agentId, forUpdate)
                .orElseThrow(() -> new NotFoundException(AgentErrorCode.NOT_FOUND, "智能体不存在：" + agentId));
    }

    private AgentVersion requireVersion(String agentId, int version, boolean forUpdate) {
        return versions.find(agentId, version, forUpdate)
                .orElseThrow(() ->
                        new NotFoundException(AgentErrorCode.VERSION_NOT_FOUND, "智能体 " + agentId + " 没有版本 " + version));
    }

    private static ConflictException published(AgentVersion version) {
        return new ConflictException(AgentErrorCode.VERSION_PUBLISHED, "版本 " + version.version() + " 已发布，不可修改、删除或再次发布");
    }
}
