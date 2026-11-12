package cn.refinex.meridian.controlplane.agent.api;

import cn.refinex.meridian.api.internal.model.ResolvedAgentVersion;
import cn.refinex.meridian.api.internal.model.ResolvedEnvironment;
import cn.refinex.meridian.api.internal.model.SkillContent;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.controlplane.agent.app.AgentConfigQuery;
import cn.refinex.meridian.controlplane.environment.domain.EnvironmentRevision;
import cn.refinex.meridian.controlplane.openapi.internal.InternalAgentConfigApi;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialFilter;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;

/** 配置分发的内部接口（InternalAgentConfig 标签，ADR-0047）：只接受 runtime。 */
@RestController
public class InternalAgentConfigApiController implements InternalAgentConfigApi {

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {};

    private final AgentConfigQuery query;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param query 配置解析用例
     * @param request 当前请求（Spring 注入的代理）
     */
    public InternalAgentConfigApiController(AgentConfigQuery query, HttpServletRequest request) {
        this.query = query;
        this.request = request;
    }

    @Override
    public ResponseEntity<ResolvedAgentVersion> resolveAgentVersion(String agentVersionId) {
        ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME);
        return ResponseEntity.ok(toDto(query.resolveVersion(agentVersionId)));
    }

    @Override
    public ResponseEntity<ResolvedAgentVersion> resolveCurrentAgentVersion(String agentId) {
        ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME);
        return ResponseEntity.ok(toDto(query.resolveCurrent(agentId)));
    }

    @Override
    public ResponseEntity<ResolvedEnvironment> getResolvedEnvironment(String environmentId, Long revision) {
        ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME);
        EnvironmentRevision found = query.environment(environmentId, revision);
        return ResponseEntity.ok(new ResolvedEnvironment()
                .tenantId(found.tenantId())
                .projectId(found.projectId())
                .environmentId(found.environmentId())
                .revision(found.revision())
                .tier(ResolvedEnvironment.TierEnum.fromValue(found.config().tier()))
                .size(found.config().size())
                .egressAllowlist(found.config().egressAllowlist()));
    }

    @Override
    public ResponseEntity<SkillContent> getSkillContent(String skillId, Integer version) {
        ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME);
        return ResponseEntity.ok(toDto(query.skill(skillId, version)));
    }

    private static ResolvedAgentVersion toDto(AgentConfigQuery.Resolved resolved) {
        Map<String, Object> spec =
                JsonMappers.lenient().readValue(resolved.version().spec(), OBJECT);
        return new ResolvedAgentVersion()
                .tenantId(resolved.version().tenantId())
                .projectId(resolved.version().projectId())
                .agentId(resolved.version().agentId())
                .agentVersionId(resolved.version().id())
                .version(resolved.version().version())
                .agentDeleted(resolved.agentDeleted())
                .spec(spec)
                .skills(resolved.skills().stream()
                        .map(InternalAgentConfigApiController::toDto)
                        .toList());
    }

    private static SkillContent toDto(AgentConfigQuery.SkillContent content) {
        return new SkillContent()
                .tenantId(content.version().tenantId())
                .projectId(content.version().projectId())
                .skillId(content.version().skillId())
                .version(content.version().version())
                .name(content.version().name())
                .files(new LinkedHashMap<>(content.files()));
    }
}
