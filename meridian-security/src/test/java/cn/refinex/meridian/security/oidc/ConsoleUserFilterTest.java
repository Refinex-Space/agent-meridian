package cn.refinex.meridian.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.error.MeridianException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

/** {@link ConsoleUserFilter}：{@code /admin/} 下的请求必须带有效的控制台令牌，失败交给异常解析器。 */
class ConsoleUserFilterTest {

    private final List<Exception> resolved = new ArrayList<>();

    private final ConsoleUserFilter filter = new ConsoleUserFilter(
            token -> "tok-owner".equals(token) ? Optional.of(new ConsoleUser("owner")) : Optional.empty(),
            (request, response, handler, error) -> {
                resolved.add(error);
                return new ModelAndView();
            });

    @Test
    void validTokenSetsTheUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/tenants");
        request.addHeader("Authorization", "Bearer tok-owner");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(ConsoleUserFilter.require(request)).isEqualTo(new ConsoleUser("owner"));
        assertThat(resolved).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/v1/tenants", "/admin;a=b/v1/tenants", "/%61dmin/v1/tenants"})
    void missingOrInvalidTokensAreRejectedOnEveryPathVariant(String path) throws Exception {
        for (String header : new String[] {null, "Bearer wrong", "Basic abc"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            if (header != null) {
                request.addHeader("Authorization", header);
            }
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).as(path + " " + header).isNull();
        }
        assertThat(resolved).hasSize(3).allSatisfy(error -> assertThat(error).isInstanceOf(MeridianException.class));
        assertThat(((MeridianException) resolved.get(1)).errorCode())
                .isEqualTo(ConsoleAuthErrorCode.INVALID_CONSOLE_TOKEN);
    }

    @Test
    void otherPathsAreNotAuthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(resolved).isEmpty();
    }
}
