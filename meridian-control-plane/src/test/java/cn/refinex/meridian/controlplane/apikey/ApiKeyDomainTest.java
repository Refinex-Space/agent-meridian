package cn.refinex.meridian.controlplane.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.controlplane.apikey.domain.ApiKey;
import cn.refinex.meridian.controlplane.apikey.domain.ApiKeyErrorCode;
import cn.refinex.meridian.controlplane.apikey.domain.ApiKeySecret;
import cn.refinex.meridian.controlplane.apikey.domain.ApiKeyStatus;
import cn.refinex.meridian.controlplane.member.domain.MemberErrorCode;
import cn.refinex.meridian.controlplane.member.domain.RoleBinding;
import cn.refinex.meridian.controlplane.project.domain.ProjectErrorCode;
import cn.refinex.meridian.controlplane.tenant.domain.TenantErrorCode;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import cn.refinex.meridian.security.rbac.Role;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** control-plane 的领域规则：API Key 的格式与状态、角色与范围的组合、错误码（ADR-0042）。 */
class ApiKeyDomainTest {

    @Test
    void secretsHaveTheLivePrefixAndFortyRandomBase62Characters() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ApiKeySecret secret = ApiKeySecret.generate(random);
            assertThat(secret.plaintext()).matches("mk_live_[0-9A-Za-z]{40}");
            assertThat(secret.visiblePrefix()).isEqualTo(secret.plaintext().substring(8, 16));
            assertThat(secret.toString()).doesNotContain(secret.plaintext().substring(16));
            seen.add(secret.plaintext());
        }
        assertThat(seen).hasSize(1000);
    }

    @Test
    void statusIsDerivedFromRevocationAndExpiry() {
        Instant now = Instant.parse("2026-11-09T00:00:00Z");
        assertThat(key(null, null).status(now)).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(key(now.plusSeconds(1), null).status(now)).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(key(now, null).status(now)).isEqualTo(ApiKeyStatus.EXPIRED);
        assertThat(key(now, now.minusSeconds(5)).status(now)).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(key(null, null).displayPrefix()).isEqualTo("mk_live_AbCd1234");
    }

    @Test
    void roleScopesAreValidated() {
        RoleBinding.validateScope(Role.TENANT_ADMIN, null);
        RoleBinding.validateScope(Role.APPROVER, "prj_1");
        assertThatThrownBy(() -> RoleBinding.validateScope(Role.TENANT_ADMIN, "prj_1"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> RoleBinding.validateScope(Role.PROJECT_VIEWER, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> RoleBinding.validateScope(Role.PLATFORM_ADMIN, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void errorCodesAreWellFormed() {
        ErrorCodes.verify(TenantErrorCode.values());
        ErrorCodes.verify(ProjectErrorCode.values());
        ErrorCodes.verify(MemberErrorCode.values());
        ErrorCodes.verify(ApiKeyErrorCode.values());
    }

    private static ApiKey key(Instant expiresAt, Instant revokedAt) {
        return new ApiKey(
                "key_1",
                "ten_1",
                "prj_1",
                "k",
                "AbCd1234",
                Set.of(ApiKeyScope.SESSIONS_READ),
                600,
                expiresAt,
                "owner",
                Instant.EPOCH,
                revokedAt);
    }
}
