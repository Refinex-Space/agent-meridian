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

    @Test
    void approximateFollowsQwenStyleTokenization() {
        assertThat(TextTokens.approximate(null)).isZero();
        assertThat(TextTokens.approximate("   ")).isZero();
        assertThat(TextTokens.approximate("差旅住宿标准")).as("6 个汉字约 4.2 个，向上取整").isEqualTo(5);
        assertThat(TextTokens.approximate("travel policy")).as("短单词各 1 个").isEqualTo(2);
        assertThat(TextTokens.approximate("internationalization")).as("20 个字母").isEqualTo(3);
        assertThat(TextTokens.approximate("500元")).as("数字逐位，汉字 0.7").isEqualTo(4);
        assertThat(TextTokens.approximate("第一条，")).as("标点 1 个").isEqualTo(4);
    }
}
