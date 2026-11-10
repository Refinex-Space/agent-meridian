package cn.refinex.meridian.controlplane.project.infra.system;

import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** 项目目录：按项目 ID 找所属租户（ADR-0044）。 */
@Repository
@CrossTenant(reason = "控制台用户用 Meridian-Project 调用公开 API 时只给出项目 ID，先按它找到所属租户，再在该租户内查角色绑定（认证查找）")
public class ProjectDirectoryRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public ProjectDirectoryRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 项目所属的租户。
     *
     * @param projectId 项目 ID
     * @return 租户 ID；项目或租户不存在、已删除时为空
     */
    public Optional<String> tenantOf(String projectId) {
        return jdbc().sql("""
                        SELECT p.tenant_id FROM cp.projects p
                          JOIN cp.tenants t ON t.id = p.tenant_id
                         WHERE p.id = :id AND p.deleted_at IS NULL AND t.deleted_at IS NULL
                        """).param("id", projectId).query(String.class).optional();
    }
}
