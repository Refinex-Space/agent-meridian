package cn.refinex.meridian.controlplane.agent.api;

import cn.refinex.meridian.api.model.Agent;
import cn.refinex.meridian.api.model.AgentPage;
import cn.refinex.meridian.api.model.AgentVersionRef;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.agent.app.AgentService;
import cn.refinex.meridian.controlplane.openapi.pub.AgentsApi;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import cn.refinex.meridian.security.apikey.PublicApiRequests;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开接口 {@code GET /v1/agents}（Agents 标签，ADR-0047）：API Key 或控制台用户（带 {@code Meridian-Project}）经 {@code ApiKeyFilter}
 * 认证，{@code PlatformContext} 已绑定为它的项目；只给出本项目发布过版本的智能体，需要 {@code sessions:read}。
 */
@RestController
public class PublicAgentsApiController implements AgentsApi {

    private final AgentService agents;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param agents 智能体用例
     * @param request 当前请求（Spring 注入的代理）
     */
    public PublicAgentsApiController(AgentService agents, HttpServletRequest request) {
        this.agents = agents;
        this.request = request;
    }

    @Override
    public ResponseEntity<AgentPage> listAgents(Integer limit, String after) {
        PublicApiRequests.require(request, ApiKeyScope.SESSIONS_READ);
        var page = agents.listPublished(PageRequest.of(limit, after));
        return ResponseEntity.ok(new AgentPage(
                page.data().stream().map(PublicAgentsApiController::toDto).toList(),
                page.hasMore(),
                page.nextCursor()));
    }

    @Override
    public ResponseEntity<Agent> getAgent(String agentId) {
        PublicApiRequests.require(request, ApiKeyScope.SESSIONS_READ);
        return ResponseEntity.ok(toDto(agents.getPublished(agentId)));
    }

    private static Agent toDto(cn.refinex.meridian.controlplane.agent.domain.Agent agent) {
        var current = agent.currentVersion();
        return new Agent()
                .id(agent.id())
                .name(agent.name())
                .description(agent.description())
                .currentVersion(new AgentVersionRef()
                        .id(current.id())
                        .version(current.version())
                        .publishedAt(Times.utc(current.publishedAt())))
                .createdAt(Times.utc(agent.createdAt()))
                .updatedAt(Times.utc(agent.updatedAt()));
    }
}
