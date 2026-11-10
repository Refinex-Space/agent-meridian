package cn.refinex.meridian.controlplane.servicetoken;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.servicetoken.infra.DirectorySigningKeys;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link DirectorySigningKeys}：没有密钥时生成并持久化，重启后沿用；轮换只保留上一把。 */
class DirectorySigningKeysTest {

    @TempDir
    Path directory;

    @Test
    void keysAreGeneratedOnceAndSurviveARestart() throws Exception {
        DirectorySigningKeys first = new DirectorySigningKeys(directory.resolve("keys"), Clock.systemUTC());
        RSAKey key = first.current();

        DirectorySigningKeys restarted = new DirectorySigningKeys(directory.resolve("keys"), Clock.systemUTC());

        assertThat(restarted.current().getKeyID()).isEqualTo(key.getKeyID());
        assertThat(restarted.publicKeys())
                .singleElement()
                .satisfies(pub -> assertThat(pub.isPrivate()).isFalse());
        try (var files = Files.list(directory.resolve("keys"))) {
            Path file = files.findFirst().orElseThrow();
            assertThat(Files.getPosixFilePermissions(file))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void rotationKeepsOnlyThePreviousKey() throws Exception {
        Instant[] now = {Instant.parse("2026-11-09T00:00:00Z")};
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        DirectorySigningKeys keys = new DirectorySigningKeys(directory, clock);
        String a = keys.current().getKeyID();
        now[0] = now[0].plusSeconds(60);
        keys.rotate();
        String b = keys.current().getKeyID();
        now[0] = now[0].plusSeconds(60);
        keys.rotate();
        String c = keys.current().getKeyID();

        assertThat(keys.publicKeys()).extracting(RSAKey::getKeyID).containsExactly(c, b);
        assertThat(new DirectorySigningKeys(directory, clock).publicKeys())
                .extracting(RSAKey::getKeyID)
                .containsExactly(c, b)
                .doesNotContain(a);
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isEqualTo(2);
        }
    }
}
