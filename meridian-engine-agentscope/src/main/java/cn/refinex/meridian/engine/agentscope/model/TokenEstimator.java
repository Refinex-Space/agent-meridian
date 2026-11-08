package cn.refinex.meridian.engine.agentscope.model;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.util.List;

/**
 * 按字符数估算 token，用于预算预扣、限流与上下文构成摘要，见架构设计「模型调用」「上下文管理」。
 *
 * <p>规则：ASCII 字符每 3 个算 1 个 token，其余字符（中文等）每个算 1 个 token；每条消息另加 8 个结构开销，每个工具调用、
 * 工具结果另加 10 个；图片、音频、视频与数据块按固定的 1500 计。
 *
 * <p>误差方向：主流分词器对英文约 4 个字符 1 个 token、对中文约 1.3 至 1.5 个字符 1 个 token，所以本估算在两种语言下都
 * <b>偏多</b>。这是有意的：预扣宁可多扣，流结束后按供应商报告的用量结算、退回多扣的部分；供应商没有报告用量时（流被取消、
 * 首包之后出错）按本估算记账，同样宁可多记。
 *
 * <p>线程安全：无状态。
 */
public final class TokenEstimator {

    /** 每条消息的结构开销（角色、分隔符等）。 */
    static final int MESSAGE_OVERHEAD = 8;

    /** 每个工具调用或工具结果的结构开销。 */
    static final int TOOL_BLOCK_OVERHEAD = 10;

    /** 媒体与数据块的固定估算值：一张常见尺寸的图片在主流模型上约 1000 到 1500 个 token。 */
    static final int MEDIA_BLOCK_TOKENS = 1_500;

    /** 每个工具定义的结构开销。 */
    static final int TOOL_SCHEMA_OVERHEAD = 10;

    private static final int ASCII_CHARS_PER_TOKEN = 3;

    private TokenEstimator() {}

    /**
     * 估算一段文本。
     *
     * @param text 文本；可为空
     * @return token 估算，不小于 0
     */
    public static int text(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint < 0x80) {
                ascii++;
            } else {
                other++;
            }
            i += Character.charCount(codePoint);
        }
        return other + (ascii + ASCII_CHARS_PER_TOKEN - 1) / ASCII_CHARS_PER_TOKEN;
    }

    /**
     * 估算一条消息。
     *
     * @param message 消息；可为空
     * @return token 估算
     */
    public static int message(Msg message) {
        if (message == null) {
            return 0;
        }
        int tokens = MESSAGE_OVERHEAD + text(message.getName());
        if (message.getContent() != null) {
            for (ContentBlock block : message.getContent()) {
                tokens += block(block);
            }
        }
        return tokens;
    }

    /**
     * 估算一组消息。
     *
     * @param messages 消息；可为空
     * @return token 估算
     */
    public static int messages(List<Msg> messages) {
        if (messages == null) {
            return 0;
        }
        int tokens = 0;
        for (Msg message : messages) {
            tokens += message(message);
        }
        return tokens;
    }

    /**
     * 估算工具定义（名字、说明与入参 schema 的 JSON）。
     *
     * @param tools 工具定义；可为空
     * @return token 估算
     */
    public static int tools(List<ToolSchema> tools) {
        if (tools == null) {
            return 0;
        }
        int tokens = 0;
        for (ToolSchema tool : tools) {
            tokens += TOOL_SCHEMA_OVERHEAD + text(tool.getName()) + text(tool.getDescription());
            if (tool.getParameters() != null) {
                tokens += text(JsonUtils.getJsonCodec().toJson(tool.getParameters()));
            }
        }
        return tokens;
    }

    /**
     * 估算一个内容块。
     *
     * @param block 内容块；可为空
     * @return token 估算
     */
    public static int block(ContentBlock block) {
        return switch (block) {
            case null -> 0;
            case TextBlock text -> text(text.getText());
            case ThinkingBlock thinking -> text(thinking.getThinking());
            case HintBlock hint -> text(hint.getHint());
            case ToolUseBlock use -> TOOL_BLOCK_OVERHEAD + text(use.getName()) + text(toolInput(use));
            case ToolResultBlock result -> {
                int tokens = TOOL_BLOCK_OVERHEAD + text(result.getName());
                if (result.getOutput() != null) {
                    for (ContentBlock output : result.getOutput()) {
                        tokens += block(output);
                    }
                }
                yield tokens;
            }
            default -> MEDIA_BLOCK_TOKENS;
        };
    }

    private static String toolInput(ToolUseBlock use) {
        if (use.getContent() != null) {
            return use.getContent();
        }
        return use.getInput() == null ? "" : JsonUtils.getJsonCodec().toJson(use.getInput());
    }
}
