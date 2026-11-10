package cn.refinex.meridian.security.oidc;

import java.util.Optional;

/**
 * 控制台用户项目权限的来源端口（ADR-0044）：角色绑定在 control-plane，其他服务经 {@link ControlPlaneProjectAccessResolver} 查询。
 * 实现可以阻塞，必须线程安全。
 */
@FunctionalInterface
public interface ProjectAccessResolver {

    /**
     * 查询权限。
     *
     * @param subject 用户标识
     * @param projectId 项目 ID
     * @return 权限；项目不存在或已删除时为空
     */
    Optional<ProjectAccess> resolve(String subject, String projectId);
}
