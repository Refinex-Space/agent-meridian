package cn.refinex.meridian.security.servicejwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

/** {@link ServiceCredentialFilter}：MVC 服务的 {@code /internal/} 与 WebFlux 版本同样要求服务凭据。 */
class ServiceCredentialFilterTest {

    private final List<Exception> resolved = new ArrayList<>();

    private final ServiceCredentialFilter filter = new ServiceCredentialFilter(
            new ServiceCredentialAuthenticator(new DevServiceCredentialResolver(new DevServiceCredentialProperties(
                    true, List.of(new DevServiceCredentialProperties.Credential("svc_dev_a", "runtime"))))),
            (request, response, handler, error) -> {
                resolved.add(error);
                return new ModelAndView();
            });

    @Test
    void validCredentialSetsTheServicePrincipal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/api-keys:verify");
        request.addHeader("Authorization", "Bearer svc_dev_a");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(ServiceCredentialFilter.require(request)).isEqualTo(new ServicePrincipal("runtime"));
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
