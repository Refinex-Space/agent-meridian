package cn.refinex.meridian.runtime.modelrouting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link TextTokens} 与 {@link ModelKind}。 */
class TextTokensTest {

    @Test
    void asciiCountsThreeCharactersPerTokenAndOtherCharactersOneEach() {
        assertThat(TextTokens.estimate((String) null)).isZero();
        assertThat(TextTokens.estimate("")).isZero();
        assertThat(TextTokens.estimate("abcd")).isEqualTo(2);
        assertThat(TextTokens.estimate("差旅标准")).isEqualTo(4);
        assertThat(TextTokens.estimate("😀a")).as("代理对算一个字符").isEqualTo(2);
        assertThat(TextTokens.estimate(List.of("abc", "住宿"))).isEqualTo(3);
    }

    @Test
    void modelKindsParseFromConfigurationValues() {
        assertThat(ModelKind.of(null)).isEqualTo(ModelKind.CHAT);
        assertThat(ModelKind.of("embedding")).isEqualTo(ModelKind.EMBEDDING);
        assertThat(ModelKind.RERANK.value()).isEqualTo("rerank");
        assertThatThrownBy(() -> ModelKind.of("vision")).isInstanceOf(IllegalArgumentException.class);
    }
}
