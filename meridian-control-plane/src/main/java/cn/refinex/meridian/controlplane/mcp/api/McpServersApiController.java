package cn.refinex.meridian.controlplane.mcp.api;

import cn.refinex.meridian.api.admin.model.McpServer;
import cn.refinex.meridian.api.admin.model.McpServerPage;
import cn.refinex.meridian.api.admin.model.McpServerRequest;
import cn.refinex.meridian.api.admin.model.McpTool;
import cn.refinex.meridian.api.admin.model.McpTransport;
import cn.refinex.meridian.api.admin.model.SideEffect;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.catalog.api.CatalogMapper;
import cn.refinex.meridian.controlplane.mcp.app.McpServerService;
import cn.refinex.meridian.controlplane.openapi.admin.McpServersApi;
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

/** MCP 服务的管理接口（McpServers 标签，ADR-0047）：只做授权声明、decode → call → map。 */
@RestController
public class McpServersApiController implements McpServersApi {

    private final McpServerService servers;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param servers MCP 服务用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public McpServersApiController(McpServerService servers, AdminRequests requests, HttpServletRequest request) {
        this.servers = servers;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<McpServer> createMcpServer(
            String tenantId, String projectId, McpServerRequest body, String idempotencyKey) {
        McpServerService.Draft draft = draft(body);
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                McpServer.class,
                () -> toDto(servers.create(AccessContext.current(request), tenantId, projectId, draft)));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<McpServerPage> listMcpServers(
            String tenantId, String projectId, Integer limit, String after) {
        var page = servers.list(tenantId, projectId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new McpServerPage(
                page.data().stream().map(McpServersApiController::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.AGENT_READ)
    public ResponseEntity<McpServer> getMcpServer(String tenantId, String projectId, String mcpServerId) {
        return ResponseEntity.ok(toDto(servers.get(tenantId, projectId, mcpServerId)));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<McpServer> updateMcpServer(
            String tenantId, String projectId, String mcpServerId, McpServerRequest body, String idempotencyKey) {
        McpServerService.Draft draft = draft(body);
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                McpServer.class,
                () -> toDto(servers.update(AccessContext.current(request), tenantId, projectId, mcpServerId, draft)));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<McpServer> probeMcpServer(
            String tenantId, String projectId, String mcpServerId, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                null,
                HttpStatus.OK,
                McpServer.class,
                () -> toDto(servers.reprobe(actor(), tenantId, projectId, mcpServerId)));
    }

    @Override
    @RequiresPermission(Permission.AGENT_WRITE)
    public ResponseEntity<Void> deleteMcpServer(
            String tenantId, String projectId, String mcpServerId, String idempotencyKey) {
        return requests.delete(
                request, tenantId, idempotencyKey, () -> servers.delete(actor(), tenantId, projectId, mcpServerId));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }

    private static McpServerService.Draft draft(McpServerRequest body) {
        Map<String, String> toolSideEffects = new LinkedHashMap<>();
        if (body.getToolSideEffects() != null) {
            body.getToolSideEffects().forEach((tool, level) -> toolSideEffects.put(tool, level.getValue()));
        }
        return new McpServerService.Draft(
                body.getName(),
                body.getTransport().getValue(),
                body.getUrl(),
                body.getHeaderSecretIds(),
                body.getSideEffect() == null ? null : body.getSideEffect().getValue(),
                toolSideEffects);
    }

    private static McpServer toDto(cn.refinex.meridian.controlplane.mcp.domain.McpServer server) {
        Map<String, SideEffect> toolSideEffects = new LinkedHashMap<>();
        server.toolSideEffects().forEach((tool, level) -> toolSideEffects.put(tool, SideEffect.fromValue(level)));
        return new McpServer()
                .id(server.id())
                .tenantId(server.tenantId())
                .projectId(server.projectId())
                .name(server.name())
                .transport(McpTransport.fromValue(server.transport()))
                .url(server.url())
                .headerSecretIds(server.headerSecretIds())
                .sideEffect(server.sideEffect() == null ? null : SideEffect.fromValue(server.sideEffect()))
                .toolSideEffects(toolSideEffects)
                .tools(server.tools().stream()
                        .map(tool -> new McpTool()
                                .name(tool.name())
                                .description(tool.description())
                                .sideEffect(SideEffect.fromValue(server.sideEffectOf(tool.name()))))
                        .toList())
                .probedAt(Times.utc(server.probedAt()))
                .lastCheck(server.lastCheck() == null ? null : CatalogMapper.toDto(server.lastCheck()))
                .createdAt(Times.utc(server.createdAt()))
                .updatedAt(Times.utc(server.updatedAt()));
    }
}
