package cn.refinex.meridian.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class SecurityWebAutoConfigurationTest {

    private static final AutoConfigurations CONFIG = AutoConfigurations.of(SecurityWebAutoConfiguration.class);

    @Test
    void servletApplicationsGetTheMvcAdapter() {
        new WebApplicationContextRunner().withConfiguration(CONFIG).run(context -> {
            assertThat(context).hasSingleBean(RequestIdFilter.class);
            assertThat(context).hasSingleBean(MvcProblemHandler.class);
            assertThat(context).doesNotHaveBean(WebFluxProblemHandler.class);
        });
    }

    @Test
    void reactiveApplicationsGetTheWebFluxAdapter() {
        new ReactiveWebApplicationContextRunner().withConfiguration(CONFIG).run(context -> {
            assertThat(context).hasSingleBean(RequestIdWebFilter.class);
            assertThat(context).hasSingleBean(WebFluxProblemHandler.class);
            assertThat(context).doesNotHaveBean(MvcProblemHandler.class);
        });
    }
}
