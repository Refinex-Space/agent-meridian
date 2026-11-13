package cn.refinex.meridian.controlplane.member.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.page.Cursor;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.member.domain.Member;
import cn.refinex.meridian.controlplane.member.domain.MemberErrorCode;
import cn.refinex.meridian.controlplane.member.domain.RoleBinding;
import cn.refinex.meridian.controlplane.member.infra.MemberRepository;
import cn.refinex.meridian.controlplane.member.infra.RoleBindingRepository;
import cn.refinex.meridian.controlplane.project.app.ProjectService;
import cn.refinex.meridian.controlplane.project.domain.ProjectDeleted;
import cn.refinex.meridian.controlplane.web.Scopes;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 成员与角色绑定的用例（架构设计「授权」，ADR-0042）。授予、撤销租户所有者与移除所有者成员另需
 * {@link Permission#OWNER_ROLE_MANAGE}，经调用方的 {@link AccessContext} 用同一套角色矩阵判定；租户始终至少保留一位所有者。
 */
@Service
public class MemberService {

    private final MemberRepository members;
    private final RoleBindingRepository bindings;
    private final ProjectService projects;
    private final AdminAudit audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param members 成员仓储
     * @param bindings 角色绑定仓储
     * @param projects 项目用例，校验项目级角色的项目
     * @param audit 审计
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public MemberService(
            MemberRepository members,
            RoleBindingRepository bindings,
            ProjectService projects,
            AdminAudit audit,
            TransactionTemplate transactions,
            Clock clock) {
        this.members = members;
        this.bindings = bindings;
        this.projects = projects;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 添加成员：给出主体标识时直接生效；只给邮箱时是邀请，用户第一次登录时按经过验证的邮箱绑定（ADR-0053）。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param subject 新成员的主体标识；可为空，此时邮箱必填
     * @param email 邮箱；可为空
     * @param displayName 显示名称；可为空
     * @return 新成员
     * @throws ValidationException 主体标识与邮箱都没有时（{@code member.identity_required}）
     * @throws ConflictException 该主体已是成员、或该邮箱已被邀请时（{@code member.already_exists}）
     */
    public Member add(String actor, String tenantId, String subject, String email, String displayName) {
        requireIdentity(subject, email, "subject");
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    Member member = insertMember(tenantId, subject, email, displayName);
                    audit.record("member.added", actor, tenantId, null, "member", member.id(), identity(member));
                    return member;
                }));
    }

    /**
     * 新租户的首个所有者：在创建租户的事务与上下文中调用。
     *
     * @param actor 操作者（平台管理员）的主体标识
     * @param tenantId 新租户 ID
     * @param subject 所有者的主体标识
     * @param email 邮箱；可为空
     * @param displayName 显示名称；可为空
     * @return 所有者成员
     */
    public Member addFirstOwner(String actor, String tenantId, String subject, String email, String displayName) {
        requireIdentity(subject, email, "owner.subject");
        Member member = insertMember(tenantId, subject, email, displayName);
        RoleBinding binding = new RoleBinding(
                Ids.newId(IdPrefix.ROLE_BINDING), tenantId, member.id(), Role.TENANT_OWNER, null, member.createdAt());
        bindings.insert(binding);
        audit.record("member.added", actor, tenantId, null, "member", member.id(), identity(member));
        audit.record(
                "role_binding.created", actor, tenantId, null, "role_binding", binding.id(), bindingDetails(binding));
        return member;
    }

    /**
     * 读取成员。
     *
     * @param tenantId 租户 ID
     * @param memberId 成员 ID
     * @return 成员
     * @throws NotFoundException 不存在时
     */
    public Member get(String tenantId, String memberId) {
        return PlatformContexts.call(Scopes.tenant(tenantId), () -> requireMember(memberId));
    }

    /**
     * 列出成员。
     *
     * @param tenantId 租户 ID
     * @param page 分页参数
     * @return 一页成员
     */
    public PageResult<Member> list(String tenantId, PageRequest page) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId), () -> PageResult.of(members.list(page), page, m -> Cursor.of(m.id())));
    }

    /**
     * 修改成员资料，只修改给出的字段。
     *
     * @param actor 操作者的主体标识
     * @param tenantId 租户 ID
     * @param memberId 成员 ID
     * @param email 新邮箱；可为空，表示不变
     * @param displayName 新显示名称；可为空，表示不变
     * @return 修改后的成员
     */
    public Member update(String actor, String tenantId, String memberId, String email, String displayName) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    Member current = requireMember(memberId);
                    Member updated = new Member(
                            current.id(),
                            tenantId,
                            current.subject(),
                            email == null ? current.email() : email,
                            displayName == null ? current.displayName() : displayName,
                            current.createdAt(),
                            clock.instant());
                    members.update(updated);
                    audit.record("member.updated", actor, tenantId, null, "member", memberId, Map.of());
                    return updated;
                }));
    }

    /**
     * 移除成员及其全部角色绑定。成员是所有者时另需所有者权限，且不能是最后一位所有者。
     *
     * @param access 调用方的授权上下文
     * @param tenantId 租户 ID
     * @param memberId 成员 ID
     */
    public void remove(AccessContext access, String tenantId, String memberId) {
        PlatformContexts.run(
                Scopes.tenant(tenantId),
                () -> transactions.executeWithoutResult(status -> {
                    Member member = requireMember(memberId);
                    if (bindings.hasTenantOwner(memberId)) {
                        access.require(Permission.OWNER_ROLE_MANAGE, null);
                        requireAnotherOwner();
                    }
                    members.delete(memberId);
                    audit.record(
                            "member.removed",
                            access.user().subject(),
                            tenantId,
                            null,
                            "member",
                            memberId,
                            identity(member));
                }));
    }

    /**
     * 授予角色。
     *
     * @param access 调用方的授权上下文
     * @param tenantId 租户 ID
     * @param memberId 成员 ID
     * @param role 角色
     * @param projectId 项目 ID；项目级角色必填
     * @return 新的角色绑定
     * @throws ConflictException 已授予时（{@code role_binding.already_exists}）
     */
    public RoleBinding grant(AccessContext access, String tenantId, String memberId, Role role, String projectId) {
        RoleBinding.validateScope(role, projectId);
        if (role == Role.TENANT_OWNER) {
            access.require(Permission.OWNER_ROLE_MANAGE, null);
        }
        if (projectId != null) {
            projects.require(tenantId, projectId);
        }
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> transactions.execute(status -> {
                    requireMember(memberId);
                    RoleBinding binding = new RoleBinding(
                            Ids.newId(IdPrefix.ROLE_BINDING), tenantId, memberId, role, projectId, clock.instant());
                    if (!bindings.insert(binding)) {
                        throw new ConflictException(MemberErrorCode.ROLE_BINDING_ALREADY_EXISTS, "该成员已有这个角色");
                    }
                    audit.record(
                            "role_binding.created",
                            access.user().subject(),
                            tenantId,
                            projectId,
                            "role_binding",
                            binding.id(),
                            bindingDetails(binding));
                    return binding;
                }));
    }

    /**
     * 列出角色绑定。
     *
     * @param tenantId 租户 ID
     * @param page 分页参数
     * @param memberId 只列出该成员的；可为空
     * @param projectId 只列出该项目的；可为空
     * @return 一页角色绑定
     */
    public PageResult<RoleBinding> listBindings(String tenantId, PageRequest page, String memberId, String projectId) {
        return PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> PageResult.of(bindings.list(page, memberId, projectId), page, b -> Cursor.of(b.id())));
    }

    /**
     * 撤销角色。撤销所有者另需所有者权限，且不能撤销最后一位。
     *
     * @param access 调用方的授权上下文
     * @param tenantId 租户 ID
     * @param bindingId 角色绑定 ID
     */
    public void revoke(AccessContext access, String tenantId, String bindingId) {
        PlatformContexts.run(
                Scopes.tenant(tenantId),
                () -> transactions.executeWithoutResult(status -> {
                    RoleBinding binding = bindings.find(bindingId)
                            .orElseThrow(() -> new NotFoundException(
                                    MemberErrorCode.ROLE_BINDING_NOT_FOUND, "角色绑定不存在：" + bindingId));
                    if (binding.role() == Role.TENANT_OWNER) {
                        access.require(Permission.OWNER_ROLE_MANAGE, null);
                        requireAnotherOwner();
                    }
                    bindings.delete(bindingId);
                    audit.record(
                            "role_binding.deleted",
                            access.user().subject(),
                            tenantId,
                            binding.projectId(),
                            "role_binding",
                            bindingId,
                            bindingDetails(binding));
                }));
    }

    /**
     * 项目删除时删除它的角色绑定（与删除项目同一事务）。
     *
     * @param event 项目删除事件
     */
    @EventListener
    public void onProjectDeleted(ProjectDeleted event) {
        PlatformContexts.run(Scopes.tenant(event.tenantId()), () -> bindings.deleteForProject(event.projectId()));
    }

    private Member insertMember(String tenantId, String subject, String email, String displayName) {
        Instant now = clock.instant();
        Member member = new Member(
                Ids.newId(IdPrefix.MEMBER),
                tenantId,
                subject,
                email == null ? "" : email.strip(),
                displayName == null ? "" : displayName,
                now,
                now);
        if (!members.insert(member)) {
            throw new ConflictException(MemberErrorCode.ALREADY_EXISTS, subject == null ? "该邮箱已有未绑定的邀请" : "该主体已是租户成员");
        }
        return member;
    }

    private Member requireMember(String memberId) {
        return members.find(memberId)
                .orElseThrow(() -> new NotFoundException(MemberErrorCode.NOT_FOUND, "成员不存在：" + memberId));
    }

    private void requireAnotherOwner() {
        // 锁住全部所有者绑定再计数：并发撤销两位所有者时第二个事务看到的是第一个提交后的结果
        if (bindings.lockOwners() <= 1) {
            throw new ConflictException(MemberErrorCode.LAST_OWNER, "租户至少要保留一位所有者");
        }
    }

    private static void requireIdentity(String subject, String email, String field) {
        if ((subject == null || subject.isBlank()) && (email == null || email.isBlank())) {
            throw new ValidationException(
                    MemberErrorCode.IDENTITY_REQUIRED, "需要用户标识或邮箱", List.of(new FieldViolation(field, "用户标识与邮箱至少给一个")));
        }
    }

    /** 审计中的成员身份：已绑定的记用户标识，邀请记邮箱。 */
    private static Map<String, Object> identity(Member member) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subject", member.subject());
        details.put("email", member.email());
        details.put("status", member.invited() ? "invited" : "active");
        return details;
    }

    private static Map<String, Object> bindingDetails(RoleBinding binding) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("member_id", binding.memberId());
        details.put("role", binding.role().value());
        details.put("project_id", binding.projectId());
        return details;
    }
}
