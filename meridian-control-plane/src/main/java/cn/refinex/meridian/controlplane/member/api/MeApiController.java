package cn.refinex.meridian.controlplane.member.api;

import cn.refinex.meridian.api.admin.model.ApiKeyScope;
import cn.refinex.meridian.api.admin.model.Me;
import cn.refinex.meridian.api.admin.model.Membership;
import cn.refinex.meridian.api.admin.model.MembershipRole;
import cn.refinex.meridian.api.admin.model.MyAccess;
import cn.refinex.meridian.api.admin.model.PermissionName;
import cn.refinex.meridian.api.admin.model.Role;
import cn.refinex.meridian.controlplane.member.app.MeQuery;
import cn.refinex.meridian.controlplane.openapi.admin.MeApi;
import cn.refinex.meridian.security.oidc.ConsoleUserFilter;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 当前控制台用户（{@code /admin/v1/me}，ADR-0053）：任何已认证的用户都可以调用。 */
@RestController
public class MeApiController implements MeApi {

    private final MeQuery query;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param query 当前用户的查询
     * @param request 当前请求（代理）
     */
    public MeApiController(MeQuery query, HttpServletRequest request) {
        this.query = query;
        this.request = request;
    }

    @Override
    @RequiresPermission(Permission.CONSOLE_ACCESS)
    public ResponseEntity<Me> getMe() {
        MeQuery.Me me = query.me(ConsoleUserFilter.require(request));
        return ResponseEntity.ok(new Me(
                me.subject(),
                me.email(),
                me.platformAdmin(),
                me.memberships().stream()
                        .map(m -> new Membership(
                                m.tenantId(),
                                m.tenantName(),
                                m.memberId(),
                                m.roles().stream()
                                        .map(r -> new MembershipRole(
                                                r.bindingId(),
                                                Role.fromValue(r.role().value()),
                                                r.projectId()))
                                        .toList()))
                        .toList()));
    }

    @Override
    @RequiresPermission(Permission.CONSOLE_ACCESS)
    public ResponseEntity<MyAccess> getMyAccess(String tenantId, String projectId) {
        MeQuery.Access access = query.access(ConsoleUserFilter.require(request), tenantId, projectId);
        return ResponseEntity.ok(new MyAccess(
                access.tenantId(),
                access.projectId(),
                access.permissions().stream()
                        .map(p -> PermissionName.fromValue(p.name().toLowerCase(Locale.ROOT)))
                        .toList(),
                access.scopes().stream()
                        .map(s -> ApiKeyScope.fromValue(s.value()))
                        .toList()));
    }
}
