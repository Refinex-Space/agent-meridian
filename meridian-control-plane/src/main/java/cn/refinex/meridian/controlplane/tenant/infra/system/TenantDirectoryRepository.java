package cn.refinex.meridian.controlplane.tenant.infra.system;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.tenant.domain.Tenant;
import cn.refinex.meridian.controlplane.tenant.infra.TenantRepository;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 租户目录：跨租户列出租户。平台管理员列出全部租户，控制台用户列出自己是成员的租户（按主体标识的认证查找）。
 */
@Repository
@CrossTenant(reason = "列出租户时还没有确定的租户：平台管理员看全部租户，控制台用户按主体标识查自己所在的租户（认证查找）")
public class TenantDirectoryRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public TenantDirectoryRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 按 ID 升序列出全部未删除的租户。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个租户
     */
    public List<Tenant> listAll(PageRequest page) {
        return jdbc().sql("""
                        SELECT * FROM cp.tenants
                         WHERE deleted_at IS NULL AND (CAST(:after AS text) IS NULL OR id > :after)
                         ORDER BY id LIMIT :limit
                        """)
                .param("after", after(page))
                .param("limit", page.fetchSize())
                .query(TenantRepository::map)
                .list();
    }

    /**
     * 按 ID 升序列出某个主体是成员的未删除租户。
     *
     * @param subject 主体标识
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个租户
     */
    public List<Tenant> listForSubject(String subject, PageRequest page) {
        return jdbc().sql("""
                        SELECT t.* FROM cp.tenants t
                          JOIN cp.members m ON m.tenant_id = t.id
                         WHERE m.subject = :subject AND t.deleted_at IS NULL
                           AND (CAST(:after AS text) IS NULL OR t.id > :after)
                         ORDER BY t.id LIMIT :limit
                        """)
                .param("subject", subject)
                .param("after", after(page))
                .param("limit", page.fetchSize())
                .query(TenantRepository::map)
                .list();
    }

    private static String after(PageRequest page) {
        return page.after() == null ? null : page.after().key(0);
    }
}
