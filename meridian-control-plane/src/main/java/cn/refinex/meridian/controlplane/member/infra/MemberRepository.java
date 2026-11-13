package cn.refinex.meridian.controlplane.member.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.member.domain.Member;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code cp.members} 的租户级仓储（架构设计「租户隔离」：成员是租户级数据）。 */
@Repository
public class MemberRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public MemberRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 插入成员；同一租户中主体已存在、或同一邮箱已有未绑定的邀请时什么都不做。
     *
     * @param member 成员
     * @return 插入时为 true；主体已是成员或邮箱已被邀请时为 false
     */
    public boolean insert(Member member) {
        return sql("""
                        INSERT INTO cp.members (id, tenant_id, subject, email, display_name, created_at, updated_at)
                        VALUES (:id, :tenant_id, :subject, :email, :display_name, :created_at, :updated_at)
                        ON CONFLICT DO NOTHING
                        """)
                        .param("id", member.id())
                        .param("subject", member.subject())
                        .param("email", member.email())
                        .param("display_name", member.displayName())
                        .param("created_at", member.createdAt())
                        .param("updated_at", member.updatedAt())
                        .update()
                == 1;
    }

    /**
     * 读取成员。
     *
     * @param memberId 成员 ID
     * @return 成员；不存在时为空
     */
    public Optional<Member> find(String memberId) {
        return sql("SELECT * FROM cp.members WHERE tenant_id = :tenant_id AND id = :id")
                .param("id", memberId)
                .query(MemberRepository::map)
                .optional();
    }

    /**
     * 按主体标识读取成员。
     *
     * @param subject 主体标识
     * @return 成员；不是成员时为空
     */
    public Optional<Member> findBySubject(String subject) {
        return sql("SELECT * FROM cp.members WHERE tenant_id = :tenant_id AND subject = :subject")
                .param("subject", subject)
                .query(MemberRepository::map)
                .optional();
    }

    /**
     * 锁住一条尚未绑定的邀请。
     *
     * @param memberId 成员 ID
     * @return 邀请；已被绑定或不存在时为空
     */
    public Optional<Member> lockInvitation(String memberId) {
        return sql("""
                        SELECT * FROM cp.members
                         WHERE tenant_id = :tenant_id AND id = :id AND subject IS NULL
                         FOR UPDATE
                        """).param("id", memberId).query(MemberRepository::map).optional();
    }

    /**
     * 把邀请绑定到用户：写入主体标识。
     *
     * @param memberId 成员 ID
     * @param subject 主体标识
     * @param now 当前时间
     */
    public void bind(String memberId, String subject, Instant now) {
        sql("""
                        UPDATE cp.members SET subject = :subject, updated_at = :now
                         WHERE tenant_id = :tenant_id AND id = :id AND subject IS NULL
                        """)
                .param("id", memberId)
                .param("subject", subject)
                .param("now", now)
                .update();
    }

    /**
     * 按 ID 升序列出成员。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个成员
     */
    public List<Member> list(PageRequest page) {
        return sql("""
                        SELECT * FROM cp.members
                         WHERE tenant_id = :tenant_id AND (CAST(:after AS text) IS NULL OR id > :after)
                         ORDER BY id LIMIT :limit
                        """)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(MemberRepository::map)
                .list();
    }

    /**
     * 修改资料。
     *
     * @param member 修改后的成员
     */
    public void update(Member member) {
        sql("""
                        UPDATE cp.members SET email = :email, display_name = :display_name, updated_at = :updated_at
                         WHERE tenant_id = :tenant_id AND id = :id
                        """)
                .param("id", member.id())
                .param("email", member.email())
                .param("display_name", member.displayName())
                .param("updated_at", member.updatedAt())
                .update();
    }

    /**
     * 删除成员；角色绑定由外键级联删除。
     *
     * @param memberId 成员 ID
     */
    public void delete(String memberId) {
        sql("DELETE FROM cp.members WHERE tenant_id = :tenant_id AND id = :id")
                .param("id", memberId)
                .update();
    }

    private static Member map(ResultSet rs, int row) throws SQLException {
        return new Member(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("subject"),
                rs.getString("email"),
                rs.getString("display_name"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"));
    }
}
