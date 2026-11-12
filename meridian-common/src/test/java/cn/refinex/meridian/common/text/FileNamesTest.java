package cn.refinex.meridian.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@link FileNames}：只保留安全的最后一段，按字节截短并保留扩展名（ADR-0050）。 */
class FileNamesTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "report.pdf|report.pdf",
                "../../etc/passwd|passwd",
                "C:\\Users\\me\\a.docx|a.docx",
                "..|file",
                ".|file",
                "  .hidden  |hidden",
                "a<b>c:d\"e?g*h.txt|abcdegh.txt",
                "invoice\u202egpj.exe|invoicegpj.exe",
                "/|file",
                "dir/|file",
                "e\u0301.txt|\u00e9.txt"
            })
    void filenamesKeepOnlyASafeLastSegment(String raw, String expected) {
        assertThat(FileNames.normalize(raw)).isEqualTo(expected);
    }

    @Test
    void longNamesAreTruncatedToTheByteLimitKeepingTheExtension() {
        String name = "报".repeat(200) + ".xlsx";

        String normalized = FileNames.normalize(name);

        assertThat(normalized.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(FileNames.MAX_BYTES);
        assertThat(normalized).endsWith(".xlsx").startsWith("报报");
        assertThat(FileNames.normalize(null)).isEqualTo(FileNames.FALLBACK);
        assertThat(FileNames.normalize("x".repeat(300)).length()).isEqualTo(FileNames.MAX_BYTES);
    }
}
