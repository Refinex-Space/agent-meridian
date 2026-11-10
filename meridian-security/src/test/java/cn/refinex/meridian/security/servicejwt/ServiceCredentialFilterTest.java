package cn.refinex.meridian.security.servicejwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ForbiddenException;
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

/** {@link ServiceCredentialFilter}：MVC 服务的 {@code /internal/} 与 WebFlux 版本同样要求服务 JWT，处理器按调用方限定。 */
class ServiceCredentialFilterTest {

    private final List<Exception> resolved = new ArrayList<>();

    private final ServiceCredentialFilter filter = new ServiceCredentialFilter(
            new ServiceCredentialAuthenticator(credential -> "jwt_for_runtime".equals(credential)
                    ? Optional.of(new ServicePrincipal("runtime"))
                    : Optional.empty()),
            (request, response, handler, error) -> {
                resolved.add(error);
                return new ModelAndView();
            });

    @Test
    void validCredentialSetsTheServicePrincipal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/api-keys:verify");
        request.addHeader("Authorization", "Bearer jwt_for_runtime");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(ServiceCredentialFilter.require(request, "runtime", "knowledge"))
                .isEqualTo(new ServicePrincipal("runtime"));
        assertThatThrownBy(() -> ServiceCredentialFilter.require(request, "egress-proxy"))
                .isInstanceOf(ForbiddenException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/v1/api-keys:verify", "/internal;a=b/v1/x", "/%69nternal/v1/x"})
    void pathVariantsStillRequireCredentials(String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(resolved).hasSize(1);
    }
}
