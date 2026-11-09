package cn.refinex.meridian.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** {@link ConsoleAuthAutoConfiguration}：开发期令牌只能在 dev、test profile 启用，没有来源时一律拒绝。 */
class ConsoleAuthAutoConfigurationTest {

    private static final String[] DEV_USER = {
        "meridian.security.dev-console-users.enabled=true",
        "meridian.security.dev-console-users.users[0].token=tok-dev",
        "meridian.security.dev-console-users.users[0].subject=owner"
    };

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConsoleAuthAutoConfiguration.class))
            .withBean(
                    "handlerExceptionResolver",
                    HandlerExceptionResolver.class,
                    () -> (req, res, h, e) -> new ModelAndView());

    @Test
    void devTokensWorkInTheDevProfile() {
        runner.withPropertyValues(DEV_USER)
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context.getBean(ConsoleUserResolver.class).resolve("tok-dev"))
                            .contains(new ConsoleUser("owner"));
                    assertThat(context).hasSingleBean(ConsoleUserFilter.class);
                });
    }

    @Test
    void enablingDevTokensOutsideDevAndTestFailsTheStartup() {
        runner.withPropertyValues(DEV_USER)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("只能在 dev 或 test profile 中启用"));
    }

    @Test
    void withoutAnySourceEveryTokenIsRejected() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(
                                context.getBean(ConsoleUserResolver.class).resolve("tok-dev"))
                        .isEmpty());
    }
}
