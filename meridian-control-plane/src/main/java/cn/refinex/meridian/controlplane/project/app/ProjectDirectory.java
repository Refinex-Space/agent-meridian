package cn.refinex.meridian.controlplane.project.app;

import cn.refinex.meridian.controlplane.project.infra.system.ProjectDirectoryRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 按项目 ID 找所属租户的用例（ADR-0044），供其他上下文在还不知道租户时使用。 */
@Service
public class ProjectDirectory {

    private final ProjectDirectoryRepository repository;

    /**
     * 创建用例。
     *
     * @param repository 跨租户的项目目录
     */
    public ProjectDirectory(ProjectDirectoryRepository repository) {
        this.repository = repository;
    }

    /**
     * 项目所属的租户。
     *
     * @param projectId 项目 ID
     * @return 租户 ID；项目或租户不存在、已删除时为空
     */
    public Optional<String> tenantOf(String projectId) {
        return repository.tenantOf(projectId);
    }
}
