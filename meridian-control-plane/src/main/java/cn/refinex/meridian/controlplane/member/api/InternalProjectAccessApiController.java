package cn.refinex.meridian.controlplane.member.api;

import cn.refinex.meridian.api.internal.model.ProjectAccess;
import cn.refinex.meridian.api.internal.model.ProjectAccessRequest;
import cn.refinex.meridian.controlplane.member.app.ProjectAccessQuery;
import cn.refinex.meridian.controlplane.openapi.internal.InternalProjectAccessApi;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialFilter;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 控制台用户项目权限的内部接口（ADR-0044）：runtime 与 knowledge 的公开 API 接受控制台用户时调用。 */
@RestController
public class InternalProjectAccessApiController implements InternalProjectAccessApi {

    private final ProjectAccessQuery query;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param query 查询用例
     * @param request 当前请求（Spring 注入的代理）
     */
    public InternalProjectAccessApiController(ProjectAccessQuery query, HttpServletRequest request) {
        this.query = query;
        this.request = request;
    }

    @Override
    public ResponseEntity<ProjectAccess> resolveProjectAccess(ProjectAccessRequest body) {
        ServiceCredentialFilter.require(request, ServiceJwts.RUNTIME, ServiceJwts.KNOWLEDGE);
        cn.refinex.meridian.security.oidc.ProjectAccess access = query.resolve(body.getSubject(), body.getProjectId());
        return ResponseEntity.ok(new ProjectAccess(
                access.tenantId(),
                access.projectId(),
                access.scopes().stream().map(ApiKeyScope::value).sorted().toList()));
    }
}
