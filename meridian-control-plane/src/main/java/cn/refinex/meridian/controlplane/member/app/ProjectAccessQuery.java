package cn.refinex.meridian.controlplane.member.app;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.controlplane.project.app.ProjectDirectory;
import cn.refinex.meridian.controlplane.project.domain.ProjectErrorCode;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import cn.refinex.meridian.security.oidc.ConsoleUser;
import cn.refinex.meridian.security.oidc.ProjectAccess;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.GrantSource;
import cn.refinex.meridian.security.rbac.PublicApiScopes;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 控制台用户在一个项目上的公开 API 作用域（内部接口 {@code project-access:resolve}，ADR-0044）：找到项目所属租户，取该用户在租户中的
 * 角色绑定，按角色矩阵换算。与管理 API 用同一个 {@link GrantSource} 与同一张矩阵。
 */
@Service
public class ProjectAccessQuery {

    private final ProjectDirectory projects;
    private final GrantSource grants;

    /**
     * 创建用例。
     *
     * @param projects 项目目录
     * @param grants 角色绑定的来源
     */
    public ProjectAccessQuery(ProjectDirectory projects, GrantSource grants) {
        this.projects = projects;
        this.grants = grants;
    }

    /**
     * 查询权限。
     *
     * @param subject 用户标识
     * @param projectId 项目 ID
     * @return 项目所属租户与作用域；没有角色时作用域为空
     * @throws NotFoundException 项目或租户不存在、已删除时（{@code project.not_found}）
     */
    public ProjectAccess resolve(String subject, String projectId) {
        String tenantId = projects.tenantOf(projectId)
                .orElseThrow(() -> new NotFoundException(ProjectErrorCode.NOT_FOUND, "项目不存在"));
        ConsoleUser user = new ConsoleUser(subject);
        AccessContext access = new AccessContext(user, tenantId, grants.grants(user, tenantId));
        Set<ApiKeyScope> scopes = PublicApiScopes.granted(access, projectId);
        return new ProjectAccess(tenantId, projectId, scopes);
    }
}
