package cn.refinex.meridian.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.common.error.MeridianException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link ApiKeyAuthenticator} 与 {@link DevApiKeyResolver}。 */
class ApiKeyAuthenticatorTest {

    private final ApiKeyAuthenticator authenticator =
            new ApiKeyAuthenticator(new DevApiKeyResolver(new DevApiKeyProperties(
                    true,
                    List.of(new DevApiKeyProperties.Key(
                            "mk_dev_a", "key_a", "ten_1", "prj_a", List.of("sessions:read", "sessions:write"))))));

    @Test
    void bearerKeyResolvesToItsPrincipalAndContext() {
        ApiKeyPrincipal principal = authenticator.authenticate("Bearer mk_dev_a");

        assertThat(principal.scopes()).containsExactlyInAnyOrder(ApiKeyScope.SESSIONS_READ, ApiKeyScope.SESSIONS_WRITE);
        assertThat(principal.toContext().tenantId()).isEqualTo("ten_1");
        assertThat(principal.toContext().projectId()).isEqualTo("prj_a");
        assertThat(principal.toContext().apiKeyId()).isEqualTo("key_a");
        assertThat(authenticator.authenticate("bearer   mk_dev_a ").apiKeyId()).isEqualTo("key_a");
    }

    @Test
    void missingOrMalformedHeaderIsUnauthenticated() {
        for (String header : new String[] {null, "", "Basic abc", "Bearer ", "Bearer " + "x".repeat(300)}) {
            assertThatThrownBy(() -> authenticator.authenticate(header))
                    .as(String.valueOf(header))
                    .isInstanceOfSatisfying(
                            MeridianException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ApiKeyErrorCode.UNAUTHENTICATED));
        }
    }

    @Test
    void unknownKeyIsInvalid() {
        assertThatThrownBy(() -> authenticator.authenticate("Bearer mk_dev_b"))
                .isInstanceOfSatisfying(
                        MeridianException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ApiKeyErrorCode.INVALID_API_KEY));
    }

    @Test
    void misconfiguredDevKeysFailFast() {
        assertThatThrownBy(() -> new DevApiKeyResolver(new DevApiKeyProperties(
                        true, List.of(new DevApiKeyProperties.Key("k", "key_a", "ten_1", "prj_a", List.of("nope"))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未知的作用域");
        assertThatThrownBy(() -> new DevApiKeyResolver(new DevApiKeyProperties(
                        true,
                        List.of(
                                new DevApiKeyProperties.Key("k", "key_a", "ten_1", "prj_a", List.of()),
                                new DevApiKeyProperties.Key("k", "key_b", "ten_1", "prj_b", List.of())))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复");
        assertThatThrownBy(() -> new DevApiKeyResolver(new DevApiKeyProperties(
                        true, List.of(new DevApiKeyProperties.Key(" ", "key_a", "ten_1", "prj_a", List.of())))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scopesAndErrorCodesAreWellFormed() {
        ErrorCodes.verify(ApiKeyErrorCode.values());
        for (ApiKeyScope scope : ApiKeyScope.values()) {
            assertThat(ApiKeyScope.fromValue(scope.value())).contains(scope);
        }
        assertThat(new ApiKeyPrincipal("key_a", "ten_1", "prj_a", Set.of()).hasScope(ApiKeyScope.SESSIONS_READ))
                .isFalse();
    }
}
