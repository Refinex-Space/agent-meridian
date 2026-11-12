package cn.refinex.meridian.runtime.file.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 文件的领域规则（ADR-0050）：文件名规范化、引用的提取、错误码。 */
class FileDomainTest {

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

    @Test
    void referencesComeFromFileBlocksAndImagesByIdOnly() {
        TurnInput input = new TurnInput.NewMessages(
                List.of(new InboundMessage(
                        "evt_1",
                        List.of(
                                new MessageContent.Text("看"),
                                new MessageContent.Image("https://e.com/a.png", null, null),
                                new MessageContent.Image(null, "fil_1", null),
                                new MessageContent.FileRef("fil_2", "a.pdf", null)))),
                null);

        assertThat(FileReferences.of(input))
                .containsExactly(
                        new FileReferences.Reference("fil_1", null, true),
                        new FileReferences.Reference("fil_2", "a.pdf", false));
        assertThat(FileReferences.of(new TurnInput.RecoveryOnly(new RecoveryNote(List.of()))))
                .isEmpty();
    }

    @Test
    void errorCodesAreRegistered() {
        ErrorCodes.verify(FileErrorCode.values());
    }
}
