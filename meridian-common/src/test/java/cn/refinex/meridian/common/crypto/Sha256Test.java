package cn.refinex.meridian.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link Sha256}：三种入参得到相同的摘要。 */
class Sha256Test {

    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void bytesTextAndFilesAgree(@TempDir Path directory) throws IOException {
        Path file = Files.write(directory.resolve("abc.txt"), "abc".getBytes(StandardCharsets.UTF_8));

        assertThat(Sha256.hex("abc")).isEqualTo(ABC);
        assertThat(Sha256.hex("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(ABC);
        assertThat(Sha256.hex(file)).isEqualTo(ABC);
    }
}
