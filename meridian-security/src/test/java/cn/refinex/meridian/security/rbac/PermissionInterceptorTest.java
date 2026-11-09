package cn.refinex.meridian.security.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.security.oidc.ConsoleUser;
import cn.refinex.meridian.security.oidc.ConsoleUserFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** {@link PermissionInterceptor}：按注解与路径变量判定，失败即关闭。 */
class PermissionInterceptorTest {

    private final List<String> lookups = new ArrayList<>();

    private final PermissionInterceptor interceptor = new PermissionInterceptor((subject, tenantId) -> {
        lookups.add(subject + "@" + tenantId);
        return "dev".equals(subject) ? Set.of(new Grant(Role.PROJECT_DEVELOPER, "prj_a")) : Set.of();
    });

    @Test
    void grantsAreLookedUpForThePathTenantAndChecked() throws Exception {
        MockHttpServletRequest request = admin("dev", Map.of("tenant_id", "ten_a", "project_id", "prj_a"));

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler("writeKeys")))
                .isTrue();
        assertThat(lookups).containsExactly("dev@ten_a");
        assertThat(AccessContext.current(request).tenantId()).isEqualTo("ten_a");
    }

    @Test
    void anotherProjectIsForbidden() throws Exception {
        MockHttpServletRequest request = admin("dev", Map.of("tenant_id", "ten_a", "project_id", "prj_b"));

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), handler("writeKeys")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void adminHandlersWithoutAnnotationAreRejected() throws Exception {
        MockHttpServletRequest request = admin("dev", Map.of("tenant_id", "ten_a"));

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), handler("unannotated")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@RequiresPermission");
    }

    @Test
    void tenantPermissionsRequireTheTenantVariable() throws Exception {
        MockHttpServletRequest request = admin("dev", Map.of());

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), handler("readTenant")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pathsOutsideTheAdminApiAreNotChecked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/api-keys:verify");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler("unannotated")))
                .isTrue();
        assertThat(lookups).isEmpty();
    }

    private static MockHttpServletRequest admin(String subject, Map<String, String> variables) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/tenants/x");
        request.setAttribute(ConsoleUserFilter.PRINCIPAL_ATTRIBUTE, new ConsoleUser(subject));
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
        return request;
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
    }

    /** 测试用的处理器。 */
    public static class Handlers {

        /** 项目级操作。 */
        @RequiresPermission(Permission.API_KEY_WRITE)
        public void writeKeys() {}

        /** 租户级操作。 */
        @RequiresPermission(Permission.TENANT_READ)
        public void readTenant() {}

        /** 漏掉注解的处理器。 */
        public void unannotated() {}
    }
}
