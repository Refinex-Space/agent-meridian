package cn.refinex.meridian.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import cn.refinex.meridian.security.apikey.PublicApiPrincipal;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link ConsoleApiAuthenticator}：公开 API 上的控制台用户必须指定项目，并在项目中有作用域（ADR-0044）。 */
class ConsoleApiAuthenticatorTest {

    private final ConsoleApiAuthenticator authenticator = new ConsoleApiAuthenticator(
            token -> "tok.alice.sig".equals(token) ? Optional.of(new ConsoleUser("alice")) : Optional.empty(),
            (subject, project) -> switch (project) {
                case "prj_dev" ->
                    Optional.of(new ProjectAccess(
                            "ten_a", "prj_dev", Set.of(ApiKeyScope.SESSIONS_READ, ApiKeyScope.SESSIONS_WRITE)));
                case "prj_none" -> Optional.of(new ProjectAccess("ten_a", "prj_none", Set.of()));
                default -> Optional.empty();
            },
            120);

    @Test
    void aUserWithARoleGetsTheProjectScopesAndAUserBucket() {
        PublicApiPrincipal principal = authenticator.authenticate("tok.alice.sig", "prj_dev");

        assertThat(principal.tenantId()).isEqualTo("ten_a");
        assertThat(principal.scopes()).containsExactlyInAnyOrder(ApiKeyScope.SESSIONS_READ, ApiKeyScope.SESSIONS_WRITE);
        assertThat(principal.rateLimitPerMinute()).isEqualTo(120);
        assertThat(principal.rateLimitBucket()).isEqualTo("user:{alice}");
        assertThat(principal.toContext().userId()).isEqualTo("alice");
        assertThat(principal.toContext().apiKeyId()).isNull();
        assertThat(principal.toContext().actor()).isEqualTo("user:alice");
    }

    @Test
    void theProjectHeaderIsRequired() {
        assertThatThrownBy(() -> authenticator.authenticate("tok.alice.sig", null))
                .isInstanceOfSatisfying(
                        MeridianException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ConsoleAuthErrorCode.PROJECT_REQUIRED));
    }

    @Test
    void noRoleAndUnknownProjectAreTheSameForbidden() {
        for (String project : new String[] {"prj_none", "prj_missing"}) {
            assertThatThrownBy(() -> authenticator.authenticate("tok.alice.sig", project))
                    .isInstanceOfSatisfying(
                            ForbiddenException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
        }
    }

    @Test
    void invalidTokensAreUnauthenticated() {
        assertThatThrownBy(() -> authenticator.authenticate("tok.bob.sig", "prj_dev"))
                .isInstanceOfSatisfying(
                        MeridianException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ConsoleAuthErrorCode.INVALID_CONSOLE_TOKEN));
    }
}
