package cn.refinex.meridian.runtime.file.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 文件的领域规则（ADR-0050）：引用的提取、错误码。文件名规范化见 meridian-common 的 {@code FileNamesTest}。 */
class FileDomainTest {

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
