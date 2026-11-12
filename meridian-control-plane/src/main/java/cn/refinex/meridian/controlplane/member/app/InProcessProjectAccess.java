package cn.refinex.meridian.controlplane.member.app;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.security.oidc.ProjectAccess;
import cn.refinex.meridian.security.oidc.ProjectAccessResolver;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * control-plane 自己的公开 API 接受控制台用户（带 {@code Meridian-Project}）时的项目权限：进程内经 {@link ProjectAccessQuery} 按角色矩阵
 * 换算作用域（ADR-0044）。它是应用提供的 {@link ProjectAccessResolver}，{@code meridian-security} 据此为 MVC 的公开 API 装配控制台用户的
 * 认证（D55）。
 *
 * <p>线程安全：无状态。
 */
@Component
public class InProcessProjectAccess implements ProjectAccessResolver {

    private final ProjectAccessQuery query;

    /**
     * 创建实现。
     *
     * @param query 项目权限的查询
     */
    public InProcessProjectAccess(ProjectAccessQuery query) {
        this.query = query;
    }

    @Override
    public Optional<ProjectAccess> resolve(String subject, String projectId) {
        try {
            return Optional.of(query.resolve(subject, projectId));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }
}
