package cn.refinex.meridian.controlplane.member.api;

import cn.refinex.meridian.api.admin.model.AddMemberRequest;
import cn.refinex.meridian.api.admin.model.CreateRoleBindingRequest;
import cn.refinex.meridian.api.admin.model.Member;
import cn.refinex.meridian.api.admin.model.MemberPage;
import cn.refinex.meridian.api.admin.model.RoleBinding;
import cn.refinex.meridian.api.admin.model.RoleBindingPage;
import cn.refinex.meridian.api.admin.model.UpdateMemberRequest;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.member.app.MemberService;
import cn.refinex.meridian.controlplane.openapi.admin.MembersApi;
import cn.refinex.meridian.controlplane.web.AdminRequests;
import cn.refinex.meridian.controlplane.web.Times;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import cn.refinex.meridian.security.rbac.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 成员与角色绑定的管理接口（{@code meridian-admin-v1.yaml} 的 Members 标签）：只做授权声明、decode → call → map。 */
@RestController
public class MembersApiController implements MembersApi {

    private final MemberService members;
    private final AdminRequests requests;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param members 成员用例
     * @param requests 写操作的幂等执行入口
     * @param request 当前请求（Spring 注入的代理）
     */
    public MembersApiController(MemberService members, AdminRequests requests, HttpServletRequest request) {
        this.members = members;
        this.requests = requests;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.MEMBER_WRITE)
    public ResponseEntity<Member> addMember(String tenantId, AddMemberRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                Member.class,
                () -> toDto(members.add(actor(), tenantId, body.getSubject(), body.getEmail(), body.getDisplayName())));
    }

    @Override
    @RequiresPermission(Permission.MEMBER_READ)
    public ResponseEntity<MemberPage> listMembers(String tenantId, Integer limit, String after) {
        var page = members.list(tenantId, PageRequest.of(limit, after));
        return ResponseEntity.ok(new MemberPage(
                page.data().stream().map(MembersApiController::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.MEMBER_READ)
    public ResponseEntity<Member> getMember(String tenantId, String memberId) {
        return ResponseEntity.ok(toDto(members.get(tenantId, memberId)));
    }

    @Override
    @RequiresPermission(Permission.MEMBER_WRITE)
    public ResponseEntity<Member> updateMember(
            String tenantId, String memberId, UpdateMemberRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.OK,
                Member.class,
                () -> toDto(members.update(
                        AccessContext.current(request), tenantId, memberId, body.getEmail(), body.getDisplayName())));
    }

    @Override
    @RequiresPermission(Permission.MEMBER_WRITE)
    public ResponseEntity<Void> removeMember(String tenantId, String memberId, String idempotencyKey) {
        return requests.delete(
                request,
                tenantId,
                idempotencyKey,
                () -> members.remove(AccessContext.current(request), tenantId, memberId));
    }

    @Override
    @RequiresPermission(Permission.MEMBER_WRITE)
    public ResponseEntity<RoleBinding> createRoleBinding(
            String tenantId, CreateRoleBindingRequest body, String idempotencyKey) {
        return requests.write(
                request,
                tenantId,
                idempotencyKey,
                body,
                HttpStatus.CREATED,
                RoleBinding.class,
                () -> toDto(members.grant(
                        AccessContext.current(request),
                        tenantId,
                        body.getMemberId(),
                        Role.fromValue(body.getRole().getValue()).orElseThrow(),
                        body.getProjectId())));
    }

    @Override
    @RequiresPermission(Permission.MEMBER_READ)
    public ResponseEntity<RoleBindingPage> listRoleBindings(
            String tenantId, Integer limit, String after, String memberId, String projectId) {
        var page = members.listBindings(tenantId, PageRequest.of(limit, after), memberId, projectId);
        return ResponseEntity.ok(new RoleBindingPage(
                page.data().stream().map(MembersApiController::toDto).toList(), page.hasMore(), page.nextCursor()));
    }

    @Override
    @RequiresPermission(Permission.MEMBER_WRITE)
    public ResponseEntity<Void> deleteRoleBinding(String tenantId, String roleBindingId, String idempotencyKey) {
        return requests.delete(
                request,
                tenantId,
                idempotencyKey,
                () -> members.revoke(AccessContext.current(request), tenantId, roleBindingId));
    }

    private String actor() {
        return AccessContext.current(request).user().subject();
    }

    private static Member toDto(cn.refinex.meridian.controlplane.member.domain.Member member) {
        return new Member(
                member.id(),
                member.tenantId(),
                member.subject(),
                member.invited() ? Member.StatusEnum.INVITED : Member.StatusEnum.ACTIVE,
                member.email(),
                member.displayName(),
                Times.utc(member.createdAt()),
                Times.utc(member.updatedAt()));
    }

    private static RoleBinding toDto(cn.refinex.meridian.controlplane.member.domain.RoleBinding binding) {
        return new RoleBinding(
                binding.id(),
                binding.tenantId(),
                binding.memberId(),
                cn.refinex.meridian.api.admin.model.Role.fromValue(
                        binding.role().value()),
                binding.projectId(),
                Times.utc(binding.createdAt()));
    }
}
