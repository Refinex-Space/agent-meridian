package cn.refinex.meridian.controlplane.member.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.member.domain.RoleBinding;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import cn.refinex.meridian.security.rbac.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code cp.role_bindings} 的租户级仓储（架构设计「租户隔离」：角色绑定是租户级数据）。 */
@Repository
public class RoleBindingRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public RoleBindingRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 插入角色绑定；同一成员同一范围的同一角色已存在时什么都不做。
     *
     * @param binding 角色绑定
     * @return 插入时为 true；已存在时为 false
     */
    public boolean insert(RoleBinding binding) {
        return sql("""
                        INSERT INTO cp.role_bindings (id, tenant_id, member_id, role, project_id, created_at)
                        VALUES (:id, :tenant_id, :member_id, :role, :binding_project_id, :created_at)
                        ON CONFLICT (member_id, role, COALESCE(project_id, '')) DO NOTHING
                        """)
                        .param("id", binding.id())
                        .param("member_id", binding.memberId())
                        .param("role", binding.role().value())
                        .param("binding_project_id", binding.projectId())
                        .param("created_at", binding.createdAt())
                        .update()
                == 1;
    }

    /**
     * 读取角色绑定。
     *
     * @param bindingId 角色绑定 ID
     * @return 角色绑定；不存在时为空
     */
    public Optional<RoleBinding> find(String bindingId) {
        return sql("SELECT * FROM cp.role_bindings WHERE tenant_id = :tenant_id AND id = :id")
                .param("id", bindingId)
                .query(RoleBindingRepository::map)
                .optional();
    }

    /**
     * 按 ID 升序列出角色绑定。
     *
     * @param page 分页参数
     * @param memberId 只列出该成员的；可为空
     * @param projectId 只列出该项目的；可为空
     * @return 最多 {@code page.fetchSize()} 个角色绑定
     */
    public List<RoleBinding> list(PageRequest page, String memberId, String projectId) {
        return sql("""
                        SELECT * FROM cp.role_bindings
                         WHERE tenant_id = :tenant_id
                           AND (CAST(:member_id AS text) IS NULL OR member_id = :member_id)
                           AND (CAST(:project_filter AS text) IS NULL OR project_id = :project_filter)
                           AND (CAST(:after AS text) IS NULL OR id > :after)
                         ORDER BY id LIMIT :limit
                        """)
                .param("member_id", memberId)
                .param("project_filter", projectId)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(RoleBindingRepository::map)
                .list();
    }

    /**
     * 列出某个主体在当前租户中的全部角色绑定（授权判定）。
     *
     * @param subject 主体标识
     * @return 角色绑定；不是成员时为空列表
     */
    public List<RoleBinding> listForSubject(String subject) {
        return sql("""
                        SELECT b.* FROM cp.role_bindings b
                          JOIN cp.members m ON m.id = b.member_id
                         WHERE b.tenant_id = :tenant_id AND m.subject = :subject
                        """)
                .param("subject", subject)
                .query(RoleBindingRepository::map)
                .list();
    }

    /**
     * 成员是否是租户所有者。
     *
     * @param memberId 成员 ID
     * @return 有所有者绑定时为 true
     */
    public boolean hasTenantOwner(String memberId) {
        return sql("""
                        SELECT EXISTS (SELECT 1 FROM cp.role_bindings
                                        WHERE tenant_id = :tenant_id AND member_id = :member_id AND role = 'tenant_owner')
                        """).param("member_id", memberId).query(Boolean.class).single();
    }

    /**
     * 锁住当前租户全部所有者的绑定并计数：移除、撤销所有者前在同一事务中检查，保证至少留下一位。
     *
     * @return 所有者绑定的数量
     */
    public int lockOwners() {
        return sql("""
                        SELECT id FROM cp.role_bindings
                         WHERE tenant_id = :tenant_id AND role = 'tenant_owner'
                           FOR UPDATE
                        """).query((rs, row) -> rs.getString("id")).list().size();
    }

    /**
     * 删除一个角色绑定。
     *
     * @param bindingId 角色绑定 ID
     */
    public void delete(String bindingId) {
        sql("DELETE FROM cp.role_bindings WHERE tenant_id = :tenant_id AND id = :id")
                .param("id", bindingId)
                .update();
    }

    /**
     * 删除某个项目的全部角色绑定（删除项目时）。
     *
     * @param projectId 项目 ID
     */
    public void deleteForProject(String projectId) {
        sql("DELETE FROM cp.role_bindings WHERE tenant_id = :tenant_id AND project_id = :binding_project_id")
                .param("binding_project_id", projectId)
                .update();
    }

    /**
     * 把一个成员的角色绑定移到另一个成员（邀请合并到已有成员，ADR-0053）；目标已有的同一角色不重复移动，随源成员删除。
     *
     * @param fromMemberId 源成员
     * @param toMemberId 目标成员
     * @return 移动的绑定数
     */
    public int reassign(String fromMemberId, String toMemberId) {
        return sql("""
                        UPDATE cp.role_bindings b SET member_id = :to_member
                         WHERE b.tenant_id = :tenant_id AND b.member_id = :from_member
                           AND NOT EXISTS (SELECT 1 FROM cp.role_bindings t
                                            WHERE t.tenant_id = :tenant_id AND t.member_id = :to_member
                                              AND t.role = b.role
                                              AND COALESCE(t.project_id, '') = COALESCE(b.project_id, ''))
                        """)
                .param("from_member", fromMemberId)
                .param("to_member", toMemberId)
                .update();
    }

    private static RoleBinding map(ResultSet rs, int row) throws SQLException {
        return new RoleBinding(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("member_id"),
                Role.fromValue(rs.getString("role")).orElseThrow(),
                rs.getString("project_id"),
                JdbcValues.instant(rs, "created_at"));
    }
}
