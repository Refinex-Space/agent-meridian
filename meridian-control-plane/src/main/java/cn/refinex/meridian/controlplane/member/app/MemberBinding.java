package cn.refinex.meridian.controlplane.member.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.member.domain.Member;
import cn.refinex.meridian.controlplane.member.infra.MemberRepository;
import cn.refinex.meridian.controlplane.member.infra.RoleBindingRepository;
import cn.refinex.meridian.controlplane.member.infra.system.MemberDirectoryRepository;
import cn.refinex.meridian.controlplane.member.infra.system.MemberDirectoryRepository.Invitation;
import cn.refinex.meridian.controlplane.web.Scopes;
import cn.refinex.meridian.security.oidc.ConsoleUser;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 首次登录时绑定按邮箱邀请的成员（ADR-0053）：用户带 OIDC 令牌访问任一管理接口时，按令牌中经过验证的邮箱找各租户未绑定的邀请，
 * 写入用户标识；该用户在这个租户中已经是成员时，把邀请的角色合并过去并删除邀请。每条邀请一个事务，锁住邀请行后再判断，并发的
 * 请求只有一个绑定成功。没有邀请时只是一次走部分索引的查询。线程安全：无状态。
 */
@Service
public class MemberBinding {

    private static final Logger log = LoggerFactory.getLogger(MemberBinding.class);

    private final MemberDirectoryRepository directory;
    private final MemberRepository members;
    private final RoleBindingRepository bindings;
    private final AdminAudit audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param directory 跨租户的成员目录
     * @param members 成员仓储
     * @param bindings 角色绑定仓储
     * @param audit 审计
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public MemberBinding(
            MemberDirectoryRepository directory,
            MemberRepository members,
            RoleBindingRepository bindings,
            AdminAudit audit,
            TransactionTemplate transactions,
            Clock clock) {
        this.directory = directory;
        this.members = members;
        this.bindings = bindings;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 绑定该用户的全部邀请。令牌没有经过验证的邮箱时什么都不做。
     *
     * @param user 控制台用户
     * @return 绑定（或合并）的邀请数
     */
    public int bindInvitations(ConsoleUser user) {
        if (user.verifiedEmail() == null) {
            return 0;
        }
        List<Invitation> invitations = directory.invitations(user.verifiedEmail());
        int bound = 0;
        for (Invitation invitation : invitations) {
            if (bind(user, invitation)) {
                bound++;
            }
        }
        return bound;
    }

    private boolean bind(ConsoleUser user, Invitation invitation) {
        Boolean bound = PlatformContexts.call(
                Scopes.tenant(invitation.tenantId()),
                () -> transactions.execute(status -> {
                    Optional<Member> locked = members.lockInvitation(invitation.memberId());
                    if (locked.isEmpty()) {
                        return false;
                    }
                    Instant now = clock.instant();
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("subject", user.subject());
                    details.put("email", locked.get().email());
                    Optional<Member> existing = members.findBySubject(user.subject());
                    if (existing.isPresent()) {
                        int moved = bindings.reassign(
                                invitation.memberId(), existing.get().id());
                        members.delete(invitation.memberId());
                        details.put("merged_into", existing.get().id());
                        details.put("role_bindings", moved);
                    } else {
                        members.bind(invitation.memberId(), user.subject(), now);
                    }
                    audit.record(
                            "member.bound",
                            user.subject(),
                            invitation.tenantId(),
                            null,
                            "member",
                            invitation.memberId(),
                            details);
                    return true;
                }));
        if (Boolean.TRUE.equals(bound)) {
            log.info("邀请已绑定 tenantId={} memberId={}", invitation.tenantId(), invitation.memberId());
        }
        return Boolean.TRUE.equals(bound);
    }
}
