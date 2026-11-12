package cn.refinex.meridian.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditActorTest {

    @Test
    void principalsAreSplitIntoConsoleUsersAndApiKeys() {
        assertThat(AuditActor.ofPrincipal("user:alice")).isEqualTo(new AuditActor("console_user", "alice"));
        assertThat(AuditActor.ofPrincipal("key_01ABC")).isEqualTo(new AuditActor("api_key", "key_01ABC"));
        assertThat(AuditActor.service("runtime")).isEqualTo(new AuditActor("service", "runtime"));
    }
}
