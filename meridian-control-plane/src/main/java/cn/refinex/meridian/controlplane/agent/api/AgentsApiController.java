package cn.refinex.meridian.controlplane.agent.api;

import cn.refinex.meridian.api.admin.model.Agent;
import cn.refinex.meridian.api.admin.model.AgentPage;
import cn.refinex.meridian.api.admin.model.AgentVersion;
import cn.refinex.meridian.api.admin.model.AgentVersionComparison;
import cn.refinex.meridian.api.admin.model.AgentVersionPage;
import cn.refinex.meridian.api.admin.model.AgentVersionRequest;
import cn.refinex.meridian.api.admin.model.AgentVersionStatus;
import cn.refinex.meridian.api.admin.model.CreateAgentRequest;
import cn.refinex.meridian.api.admin.model.PublishedVersionRef;
import cn.refinex.meridian.api.admin.model.SpecChange;
import cn.refinex.meridian.api.admin.model.UpdateAgentRequest;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.agent.app.AgentService;
import cn.refinex.meridian.controlplane.agent.app.AgentVersionService;
import cn.refinex.meridian.controlplane.openapi.admin.AgentsApi;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/** 智能体与版本的管理接口（Agents 标签，ADR-0047）：只做授权声明、decode → call → map。 */
@RestController
public class AgentsApiController implements AgentsApi {

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {};

    private final AgentService agents;
    private final AgentVersionService versions;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param agents 智能体用例
     * @param versions 版本用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public AgentsApiController(
            AgentService agents, AgentVersionService versions, AdminRequests requests, HttpServletRequest request) {
        this.agents = agents;
        this.versions = versions;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<Agent> createAgent(
            String tenantId, String projectId, CreateAgentRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                Agent.class,
                () -> toDto(agents.create(actor(), tenantId, projectId, body.getName(), body.getDescription())));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<AgentPage> listAgents(String tenantId, String projectId, Integer limit, String after) {
        var page = agents.list(tenantId, projectId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new AgentPage(
                page.data().stream().map(AgentsApiController::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<Agent> getAgent(String tenantId, String projectId, String agentId) {
        return ResponseEntity.ok(toDto(agents.get(tenantId, projectId, agentId)));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<Agent> updateAgent(
            String tenantId, String projectId, String agentId, UpdateAgentRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                Agent.class,
                () -> toDto(
                        agents.update(actor(), tenantId, projectId, agentId, body.getName(), body.getDescription())));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<Void> deleteAgent(String tenantId, String projectId, String agentId, String idempotencyKey) {
        return requests.delete(
                request, tenantId, idempotencyKey, () -> agents.delete(actor(), tenantId, projectId, agentId));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<AgentVersion> createAgentVersion(
            String tenantId, String projectId, String agentId, AgentVersionRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                AgentVersion.class,
                () -> toDto(versions.createDraft(actor(), tenantId, projectId, agentId, body.getSpec())));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<AgentVersionPage> listAgentVersions(
            String tenantId, String projectId, String agentId, Integer limit, String after) {
        var page = versions.list(tenantId, projectId, agentId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new AgentVersionPage(
                page.data().stream().map(AgentsApiController::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<AgentVersion> getAgentVersion(
            String tenantId, String projectId, String agentId, Integer version) {
        return ResponseEntity.ok(toDto(versions.get(tenantId, projectId, agentId, version)));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<AgentVersion> updateAgentVersion(
            String tenantId,
            String projectId,
            String agentId,
            Integer version,
            AgentVersionRequest body,
            String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                AgentVersion.class,
                () -> toDto(versions.updateDraft(actor(), tenantId, projectId, agentId, version, body.getSpec())));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<Void> deleteAgentVersion(
            String tenantId, String projectId, String agentId, Integer version, String idempotencyKey) {
        return requests.delete(
                request,
                tenantId,
                idempotencyKey,
                () -> versions.deleteDraft(actor(), tenantId, projectId, agentId, version));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<AgentVersion> publishAgentVersion(
            String tenantId, String projectId, String agentId, Integer version, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                null,
                HttpStatus.OK,
                AgentVersion.class,
                () -> toDto(versions.publish(actor(), tenantId, projectId, agentId, version)));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<AgentVersionComparison> compareAgentVersions(
            Integer base, Integer target, String tenantId, String projectId, String agentId) {
        var changes = versions.compare(tenantId, projectId, agentId, base, target);
        return ResponseEntity.ok(new AgentVersionComparison()
                .agentId(agentId)
                .baseVersion(base)
                .targetVersion(target)
                .changes(changes.stream()
                        .map(change -> new SpecChange()
                                .path(change.path())
                                .kind(SpecChange.KindEnum.fromValue(
                                        change.kind().value()))
                                .before(plain(change.before()))
                                .after(plain(change.after())))
                        .toList()));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }

    /** Jackson 树转成普通的 Java 值（映射、列表、标量），交给响应序列化。 */
    private static Object plain(JsonNode node) {
        return node == null ? null : JsonMappers.lenient().treeToValue(node, Object.class);
    }

    static Agent toDto(cn.refinex.meridian.controlplane.agent.domain.Agent agent) {
        var current = agent.currentVersion();
        return new Agent()
                .id(agent.id())
                .tenantId(agent.tenantId())
                .projectId(agent.projectId())
                .name(agent.name())
                .description(agent.description())
                .currentVersion(
                        current == null
                                ? null
                                : new PublishedVersionRef()
                                        .id(current.id())
                                        .version(current.version())
                                        .publishedAt(Times.utc(current.publishedAt())))
                .createdAt(Times.utc(agent.createdAt()))
                .updatedAt(Times.utc(agent.updatedAt()));
    }

    static AgentVersion toDto(cn.refinex.meridian.controlplane.agent.domain.AgentVersion version) {
        Map<String, Object> spec = JsonMappers.lenient().readValue(version.spec(), OBJECT);
        return new AgentVersion()
                .id(version.id())
                .agentId(version.agentId())
                .version(version.version())
                .status(AgentVersionStatus.fromValue(version.status().value()))
                .spec(spec)
                .createdBy(version.createdBy())
                .createdAt(Times.utc(version.createdAt()))
                .updatedAt(Times.utc(version.updatedAt()))
                .publishedBy(version.publishedBy())
                .publishedAt(version.publishedAt() == null ? null : Times.utc(version.publishedAt()));
    }
}
