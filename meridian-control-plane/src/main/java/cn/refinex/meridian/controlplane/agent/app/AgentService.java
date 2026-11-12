package cn.refinex.meridian.controlplane.agent.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.agent.domain.Agent;
import cn.refinex.meridian.controlplane.agent.domain.AgentErrorCode;
import cn.refinex.meridian.controlplane.agent.infra.AgentRepository;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
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
 * 智能体的管理用例与公开查询（ADR-0047）：名字在项目内唯一；删除是软删除——不能再用它创建会话，运行中的会话继续使用已绑定的版本，提交后
 * 经 {@code config.invalidate} 通知 runtime。公开接口只给出发布过版本的智能体。
 */
@Service
public class AgentService {

    private final AgentRepository agents;
    private final ProjectService projects;
    private final AdminAudit audit;
    private final ConfigInvalidations invalidations;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param agents 智能体仓储
     * @param projects 项目用例，确认项目存在
     * @param audit 审计
     * @param invalidations 配置变更广播
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public AgentService(
            AgentRepository agents,
            ProjectService projects,
            AdminAudit audit,
            ConfigInvalidations invalidations,
            TransactionTemplate transactions,
            Clock clock) {
        this.agents = agents;
        this.projects = projects;
        this.audit = audit;
        this.invalidations = invalidations;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 创建智能体（没有版本）。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param name 名字
     * @param description 说明；可为空
     * @return 新智能体
     */
    public Agent create(String actor, String tenantId, String projectId, String name, String description) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    Instant now = clock.instant();
                    Agent agent = new Agent(
                            Ids.newId(IdPrefix.AGENT), tenantId, projectId, name, description, actor, now, now, null);
                    try {
                        agents.insert(agent);
                    } catch (DuplicateKeyException e) {
                        throw nameTaken(name);
                    }
                    audit.record("agent.created", actor, tenantId, projectId, "agent", agent.id(), details(agent));
                    return agent;
                }));
    }

    /**
     * 读取智能体。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id 智能体 ID
     * @return 智能体，带当前版本
     */
    public Agent get(String tenantId, String projectId, String id) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(Scopes.project(tenantId, projectId), () -> require(id));
    }

    /**
     * 列出智能体。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param page 分页参数
     * @return 一页智能体
     */
    public PageResult<Agent> list(String tenantId, String projectId, PageRequest page) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> PageResult.of(agents.list(page, false), page, a -> Cursor.of(a.id())));
    }

    /**
     * 修改名字或说明：只改给出的字段。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id 智能体 ID
     * @param name 新名字；为空表示不改
     * @param description 新说明；为空表示不改
     * @return 修改后的智能体
     */
    public Agent update(String actor, String tenantId, String projectId, String id, String name, String description) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    Agent current = agents.find(id, true).orElseThrow(() -> notFound(id));
                    String newName = name == null ? current.name() : name;
                    String newDescription = description == null ? current.description() : description;
                    try {
                        agents.update(id, newName, newDescription, clock.instant());
                    } catch (DuplicateKeyException e) {
                        throw nameTaken(newName);
                    }
                    Agent updated = require(id);
                    audit.record("agent.updated", actor, tenantId, projectId, "agent", id, details(updated));
                    return updated;
                }));
    }

    /**
     * 软删除。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id 智能体 ID
     */
    public void delete(String actor, String tenantId, String projectId, String id) {
        projects.require(tenantId, projectId);
        PlatformContexts.run(
                Scopes.project(tenantId, projectId),
                () -> transactions.executeWithoutResult(status -> {
                    Agent agent = agents.find(id, true).orElseThrow(() -> notFound(id));
                    agents.softDelete(id, clock.instant());
                    audit.record("agent.deleted", actor, tenantId, projectId, "agent", id, details(agent));
                    invalidations.resourceChanged(ConfigInvalidations.AGENT, tenantId, id);
                }));
    }

    /**
     * 公开接口：当前项目中发布过版本的智能体。调用方已绑定 API Key 或控制台用户的 {@code PlatformContext}。
     *
     * @param page 分页参数
     * @return 一页智能体
     */
    public PageResult<Agent> listPublished(PageRequest page) {
        return PageResult.of(agents.list(page, true), page, a -> Cursor.of(a.id()));
    }

    /**
     * 公开接口：当前项目中发布过版本的一个智能体。调用方已绑定 {@code PlatformContext}。
     *
     * @param id 智能体 ID
     * @return 智能体
     * @throws NotFoundException 不存在、已删除或没有发布过版本时（{@code agent.not_found}）
     */
    public Agent getPublished(String id) {
        return agents.find(id, false).filter(a -> a.currentVersion() != null).orElseThrow(() -> notFound(id));
    }

    private Agent require(String id) {
        return agents.find(id, false).orElseThrow(() -> notFound(id));
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException(AgentErrorCode.NOT_FOUND, "智能体不存在：" + id);
    }

    private static ConflictException nameTaken(String name) {
        return new ConflictException(AgentErrorCode.NAME_TAKEN, "项目内已有名为 " + name + " 的智能体");
    }

    private static Map<String, Object> details(Agent agent) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", agent.name());
        details.put("description", agent.description());
        return details;
    }
}
