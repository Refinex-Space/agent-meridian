package cn.refinex.meridian.engine.agentscope.assembly;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 以消息形式注入的平台动态内容（架构设计「上下文管理」：时间等动态内容以消息注入，不改系统提示词前缀，ADR-0035）。
 *
 * <p>每个以新消息开始的轮次，在用户消息之前放一条 {@code <platform_context>} 用户消息，目前只有当前时间（UTC，精确到秒）。
 * 它随输入写进 {@code AgentState} 的上下文，元数据带 {@link #METADATA_KEY}，上下文构成把它算作平台注入内容。
 */
public final class PlatformContextMessage {

    /** 标签名，平台基础提示词声明它的含义。 */
    public static final String TAG = "platform_context";

    /** 消息元数据中的标记键。 */
    public static final String METADATA_KEY = "meridian_platform_context";

    private PlatformContextMessage() {}

    /**
     * 创建平台上下文消息。
     *
     * @param now 当前时间
     * @return 用户消息
     */
    public static Msg of(Instant now) {
        String text = "<" + TAG + ">\nCurrent time: " + now.truncatedTo(ChronoUnit.SECONDS) + " (UTC)\n</" + TAG + ">";
        return Msg.builder()
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(text).build()))
                .metadata(Map.of(METADATA_KEY, true))
                .build();
    }
}
