package cn.refinex.meridian.engine.agentscope.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 按字符数的 token 估算：误差方向是偏多（架构设计「模型调用」中的预扣）。 */
class TokenEstimatorTest {

    @Test
    void asciiCountsOneTokenPerThreeCharactersAndOtherCharactersOneEach() {
        assertThat(TokenEstimator.text(null)).isZero();
        assertThat(TokenEstimator.text("")).isZero();
        assertThat(TokenEstimator.text("abcdef")).isEqualTo(2);
        assertThat(TokenEstimator.text("abcdefg")).isEqualTo(3);
        assertThat(TokenEstimator.text("你好世界")).isEqualTo(4);
        assertThat(TokenEstimator.text("订单 123")).isEqualTo(2 + 2);
        assertThat(TokenEstimator.text("😀")).as("按码点计").isEqualTo(1);
    }

    @Test
    void estimatesErrOnTheHighSideForEnglishAndChinese() {
        String english = "The quick brown fox jumps over the lazy dog. ".repeat(10);
        String chinese = "企业内的多租户智能体平台负责治理模型与预算。".repeat(10);

        assertThat(TokenEstimator.text(english))
                .as("英文约 4 个字符 1 个 token，估算按 3 个")
                .isGreaterThanOrEqualTo(english.length() / 4);
        assertThat(TokenEstimator.text(chinese))
                .as("中文约 1.3 至 1.5 个字符 1 个 token，估算按 1 个")
                .isGreaterThanOrEqualTo((int) (chinese.length() / 1.3));
    }

    @Test
    void messagesAddStructureOverheadAndMediaHasAFixedCost() {
        Msg text = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("你好").build())
                .build();
        Msg image = Msg.builder()
                .role(MsgRole.USER)
                .content(ImageBlock.builder()
                        .source(URLSource.builder()
                                .url("https://example.com/a.png")
                                .build())
                        .build())
                .build();

        assertThat(TokenEstimator.message(text)).isEqualTo(TokenEstimator.MESSAGE_OVERHEAD + 2);
        assertThat(TokenEstimator.message(image))
                .isEqualTo(TokenEstimator.MESSAGE_OVERHEAD + TokenEstimator.MEDIA_BLOCK_TOKENS);
        assertThat(TokenEstimator.messages(List.of(text, image)))
                .isEqualTo(TokenEstimator.message(text) + TokenEstimator.message(image));
    }

    @Test
    void toolSchemasCountNameDescriptionAndParameters() {
        ToolSchema schema = ToolSchema.builder()
                .name("lookup_order")
                .description("查询订单")
                .parameters(Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string"))))
                .build();

        assertThat(TokenEstimator.tools(List.of(schema)))
                .isGreaterThan(TokenEstimator.TOOL_SCHEMA_OVERHEAD + TokenEstimator.text("lookup_order") + 4);
        assertThat(TokenEstimator.tools(null)).isZero();
    }
}
