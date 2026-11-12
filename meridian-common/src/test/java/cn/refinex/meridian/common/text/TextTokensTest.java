package cn.refinex.meridian.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link TextTokens}：按字符数估算 token。 */
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
}
