package cn.refinex.meridian.controlplane.mcp.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.error.ReferencedException;
import cn.refinex.meridian.common.error.ResourceReference;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.agent.domain.SpecReference;
import cn.refinex.meridian.controlplane.agent.infra.AgentVersionRefRepository;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.mcp.domain.McpErrorCode;
import cn.refinex.meridian.controlplane.mcp.domain.McpServer;
import cn.refinex.meridian.controlplane.mcp.domain.McpTool;
import cn.refinex.meridian.controlplane.mcp.infra.McpServerRepository;
import cn.refinex.meridian.controlplane.project.app.ProjectService;
import cn.refinex.meridian.controlplane.secret.app.SecretResolution;
import cn.refinex.meridian.controlplane.secret.app.SecretService;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.controlplane.web.Endpoints;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MCP 服务的管理用例（ADR-0047）：端点按地址策略校验（D32：链路本地、云元数据地址始终拒绝），请求头凭据必须是本项目可用的凭据；登记、修改
 * 与重新探测时以 control-plane 的身份解析凭据明文（审计 {@code secret.resolved}），连接一次服务并保存工具清单，失败时不保存。按工具标注
 * 的副作用等级只能针对清单中的工具。删除时被未删除智能体的已发布版本引用则拒绝；已发布的版本持有快照，修改不影响它们。
 */
@Service
public class McpServerService {

    /** 以 control-plane 自己的身份解析凭据（审计中的调用方服务名）。 */
    static final String CALLER = "control-plane";

    /** 请求头名：字母、数字、短横线。 */
    private static final Pattern HEADER = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private final McpServerRepository servers;
    private final McpProbe probe;
    private final SecretService secrets;
    private final SecretResolution resolution;
    private final AgentVersionRefRepository references;
    private final ProjectService projects;
    private final EndpointPolicy endpoints;
    private final AdminAudit audit;
    private final ConfigInvalidations invalidations;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param servers MCP 服务仓储
     * @param probe 探测
     * @param secrets 凭据用例，校验引用
     * @param resolution 凭据解析，探测时取请求头明文
     * @param references 已发布版本的引用，删除前检查
     * @param projects 项目用例，确认项目存在
     * @param endpoints 端点策略
     * @param audit 审计
     * @param invalidations 配置变更广播
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public McpServerService(
            McpServerRepository servers,
            McpProbe probe,
            SecretService secrets,
            SecretResolution resolution,
            AgentVersionRefRepository references,
            ProjectService projects,
            EndpointPolicy endpoints,
            AdminAudit audit,
            ConfigInvalidations invalidations,
            TransactionTemplate transactions,
            Clock clock) {
        this.servers = servers;
        this.probe = probe;
        this.secrets = secrets;
        this.resolution = resolution;
        this.references = references;
        this.projects = projects;
        this.endpoints = endpoints;
        this.audit = audit;
        this.invalidations = invalidations;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 登记：校验、探测并保存工具清单。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param draft 内容
     * @return 新的 MCP 服务
     */
    public McpServer create(String actor, String tenantId, String projectId, Draft draft) {
        projects.require(tenantId, projectId);
        List<McpTool> tools = validateAndProbe(tenantId, projectId, draft);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    Instant now = clock.instant();
                    McpServer server = draft.toServer(
                            Ids.newId(IdPrefix.MCP_SERVER), tenantId, projectId, tools, actor, now, now, now);
                    try {
                        servers.insert(server);
                    } catch (DuplicateKeyException e) {
                        throw nameTaken(draft.name());
                    }
                    audit.record(
                            "mcp_server.created",
                            actor,
                            tenantId,
                            projectId,
                            "mcp_server",
                            server.id(),
                            details(server));
                    return server;
                }));
    }

    /**
     * 整体替换并重新探测。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id MCP 服务 ID
     * @param draft 新内容
     * @return 修改后的 MCP 服务
     */
    public McpServer update(String actor, String tenantId, String projectId, String id, Draft draft) {
        projects.require(tenantId, projectId);
        PlatformContexts.call(Scopes.project(tenantId, projectId), () -> require(id, null));
        List<McpTool> tools = validateAndProbe(tenantId, projectId, draft);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    McpServer current = require(id, "UPDATE");
                    Instant now = clock.instant();
                    McpServer updated = draft.toServer(
                            id, tenantId, projectId, tools, current.createdBy(), current.createdAt(), now, now);
                    try {
                        servers.update(updated);
                    } catch (DuplicateKeyException e) {
                        throw nameTaken(draft.name());
                    }
                    audit.record("mcp_server.updated", actor, tenantId, projectId, "mcp_server", id, details(updated));
                    invalidations.resourceChanged(ConfigInvalidations.MCP_SERVER, tenantId, id);
                    return updated;
                }));
    }

    /**
     * 按已保存的配置重新探测并保存工具清单。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id MCP 服务 ID
     * @return 带新清单的 MCP 服务
     */
    public McpServer reprobe(String actor, String tenantId, String projectId, String id) {
        projects.require(tenantId, projectId);
        McpServer current = PlatformContexts.call(Scopes.project(tenantId, projectId), () -> require(id, null));
        List<McpTool> tools = probe(tenantId, projectId, current.transport(), current.url(), current.headerSecretIds());
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> transactions.execute(status -> {
                    McpServer locked = require(id, "UPDATE");
                    Instant now = clock.instant();
                    McpServer probed = new McpServer(
                            locked.id(),
                            tenantId,
                            projectId,
                            locked.name(),
                            locked.transport(),
                            locked.url(),
                            locked.headerSecretIds(),
                            locked.sideEffect(),
                            locked.toolSideEffects(),
                            tools,
                            now,
                            locked.createdBy(),
                            locked.createdAt(),
                            now);
                    servers.update(probed);
                    audit.record("mcp_server.probed", actor, tenantId, projectId, "mcp_server", id, details(probed));
                    invalidations.resourceChanged(ConfigInvalidations.MCP_SERVER, tenantId, id);
                    return probed;
                }));
    }

    /**
     * 读取。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id MCP 服务 ID
     * @return MCP 服务
     */
    public McpServer get(String tenantId, String projectId, String id) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(Scopes.project(tenantId, projectId), () -> require(id, null));
    }

    /**
     * 列出。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param page 分页参数
     * @return 一页 MCP 服务
     */
    public PageResult<McpServer> list(String tenantId, String projectId, PageRequest page) {
        projects.require(tenantId, projectId);
        return PlatformContexts.call(
                Scopes.project(tenantId, projectId),
                () -> PageResult.of(servers.list(page), page, s -> Cursor.of(s.id())));
    }

    /**
     * 删除：被未删除智能体的已发布版本引用时以 {@code mcp_server.in_use} 拒绝并列出它们。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param id MCP 服务 ID
     */
    public void delete(String actor, String tenantId, String projectId, String id) {
        projects.require(tenantId, projectId);
        PlatformContexts.run(
                Scopes.project(tenantId, projectId),
                () -> transactions.executeWithoutResult(status -> {
                    McpServer server = require(id, "UPDATE");
                    List<ResourceReference> referencedBy = PlatformContexts.call(
                            Scopes.tenant(tenantId), () -> references.referencing(SpecReference.Type.MCP_SERVER, id));
                    if (!referencedBy.isEmpty()) {
                        throw new ReferencedException(
                                McpErrorCode.IN_USE, "MCP 服务 " + server.name() + " 仍被已发布的智能体版本引用", referencedBy);
                    }
                    servers.delete(id);
                    audit.record("mcp_server.deleted", actor, tenantId, projectId, "mcp_server", id, details(server));
                    invalidations.resourceChanged(ConfigInvalidations.MCP_SERVER, tenantId, id);
                }));
    }

    /** 校验端点、请求头与凭据，探测后校验按工具标注的副作用等级都针对清单中的工具。 */
    private List<McpTool> validateAndProbe(String tenantId, String projectId, Draft draft) {
        Endpoints.check(endpoints, draft.url(), "url");
        for (Map.Entry<String, String> header : draft.headerSecretIds().entrySet()) {
            if (!HEADER.matcher(header.getKey()).matches()) {
                throw invalid("header_secret_ids", "请求头名只能是字母、数字、短横线：" + header.getKey());
            }
            secrets.requireUsable(tenantId, projectId, header.getValue(), "header_secret_ids." + header.getKey());
        }
        List<McpTool> tools = probe(tenantId, projectId, draft.transport(), draft.url(), draft.headerSecretIds());
        Set<String> names = tools.stream().map(McpTool::name).collect(Collectors.toSet());
        List<FieldViolation> violations = new ArrayList<>();
        for (String tool : draft.toolSideEffects().keySet()) {
            if (!names.contains(tool)) {
                violations.add(new FieldViolation("tool_side_effects." + tool, "服务没有这个工具：" + tool));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(CommonErrorCode.VALIDATION_FAILED, "按工具标注的副作用等级不合法", violations);
        }
        return tools;
    }

    private List<McpTool> probe(
            String tenantId, String projectId, String transport, String url, Map<String, String> headerSecretIds) {
        Map<String, String> headers = new TreeMap<>();
        headerSecretIds.forEach((name, secretId) -> headers.put(
                name, resolution.resolve(CALLER, tenantId, projectId, secretId).value()));
        try {
            return probe.probe(transport, url, headers);
        } catch (McpProbe.McpProbeException e) {
            throw new MeridianException(McpErrorCode.PROBE_FAILED, e.getMessage(), e);
        }
    }

    private McpServer require(String id, String lock) {
        return servers.find(id, lock)
                .orElseThrow(() -> new NotFoundException(McpErrorCode.NOT_FOUND, "MCP 服务不存在：" + id));
    }

    private static ConflictException nameTaken(String name) {
        return new ConflictException(McpErrorCode.NAME_TAKEN, "项目内已有名为 " + name + " 的 MCP 服务");
    }

    private static ValidationException invalid(String field, String reason) {
        return new ValidationException(
                CommonErrorCode.VALIDATION_FAILED, reason, List.of(new FieldViolation(field, reason)));
    }

    private static Map<String, Object> details(McpServer server) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", server.name());
        details.put("transport", server.transport());
        details.put("url", server.url());
        details.put("header_secret_ids", server.headerSecretIds());
        details.put("tools", server.tools().size());
        return details;
    }

    /**
     * 管理接口给出的内容。
     *
     * @param name 服务名
     * @param transport 传输
     * @param url 端点
     * @param headerSecretIds 请求头 → 凭据 ID
     * @param sideEffect 服务级副作用等级；可为空
     * @param toolSideEffects 按工具标注的副作用等级
     */
    public record Draft(
            String name,
            String transport,
            String url,
            Map<String, String> headerSecretIds,
            String sideEffect,
            Map<String, String> toolSideEffects) {

        /** 创建内容，空映射代替空值。 */
        public Draft {
            headerSecretIds = headerSecretIds == null ? Map.of() : Map.copyOf(headerSecretIds);
            toolSideEffects = toolSideEffects == null ? Map.of() : Map.copyOf(toolSideEffects);
        }

        McpServer toServer(
                String id,
                String tenantId,
                String projectId,
                List<McpTool> tools,
                String createdBy,
                Instant createdAt,
                Instant updatedAt,
                Instant probedAt) {
            return new McpServer(
                    id,
                    tenantId,
                    projectId,
                    name,
                    transport,
                    url,
                    headerSecretIds,
                    sideEffect,
                    toolSideEffects,
                    tools,
                    probedAt,
                    createdBy,
                    createdAt,
                    updatedAt);
        }
    }
}
