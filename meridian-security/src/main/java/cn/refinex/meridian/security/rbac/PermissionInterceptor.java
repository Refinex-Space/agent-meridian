package cn.refinex.meridian.security.rbac;

import cn.refinex.meridian.security.oidc.ConsoleUser;
import cn.refinex.meridian.security.oidc.ConsoleUserFilter;
import cn.refinex.meridian.security.web.RequestPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 管理 API 的声明式授权（ADR-0042）：路由确定处理器之后、调用之前，按处理器方法上的 {@link RequiresPermission} 与路径中的
 * {@code tenant_id}、{@code project_id} 判定当前用户能否执行。
 *
 * <p>失败即关闭：{@code /admin/} 下的处理器没有注解、或注解要求的路径变量不存在时，按服务端错误拒绝；没有权限时 403
 * {@code access.forbidden}，由 {@code MvcProblemHandler} 输出。判定通过后把 {@link AccessContext} 写入请求属性。
 */
public final class PermissionInterceptor implements HandlerInterceptor {

    /** 租户的路径变量名，与管理 API 规范一致。 */
    public static final String TENANT_VARIABLE = "tenant_id";

    /** 项目的路径变量名，与管理 API 规范一致。 */
    public static final String PROJECT_VARIABLE = "project_id";

    private final GrantSource grants;

    /**
     * 创建拦截器。
     *
     * @param grants 角色绑定的来源
     */
    public PermissionInterceptor(GrantSource grants) {
        this.grants = grants;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)
                || !ConsoleUserFilter.ADMIN_SEGMENT.equals(
                        RequestPaths.firstSegment(request.getRequestURI(), request.getContextPath()))) {
            return true;
        }
        RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
        if (required == null) {
            throw new IllegalStateException("管理 API 处理器缺少 @RequiresPermission：" + method.getShortLogMessage());
        }
        Permission permission = required.value();
        ConsoleUser user = ConsoleUserFilter.require(request);
        Map<String, String> variables = pathVariables(request);
        String tenantId = variables.get(TENANT_VARIABLE);
        String projectId = variables.get(PROJECT_VARIABLE);
        if ((permission.level() == Permission.Level.TENANT && tenantId == null)
                || (permission.level() == Permission.Level.PROJECT && (tenantId == null || projectId == null))) {
            throw new IllegalStateException(permission + " 需要路径变量 tenant_id"
                    + (permission.level() == Permission.Level.PROJECT ? " 与 project_id" : "") + "："
                    + method.getShortLogMessage());
        }
        AccessContext context = new AccessContext(user, tenantId, grants.grants(user, tenantId));
        context.require(permission, projectId);
        request.setAttribute(AccessContext.ATTRIBUTE, context);
        return true;
    }

    @SuppressWarnings("unchecked") // HandlerMapping 规定该属性是 Map<String, String>
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }
}
