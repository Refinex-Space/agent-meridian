package cn.refinex.meridian.engine.agentscope.assembly;

/**
 * 平台统一维护的压缩摘要提示词，见架构设计「上下文管理」：摘要会替换被压缩的历史，必须保留目标、关键决定、产物、未完成事项、下一步。
 *
 * <p>{@code {messages}} 是 2.0.4 {@code ConversationCompactor} 的占位符，压缩时替换为被压缩部分的对话文本。结构参考了
 * AgentScope 2.0.4 {@code CompactionConfig.DEFAULT_SUMMARY_PROMPT}（Apache-2.0）的分节写法，按平台要求的五项重写。
 */
public final class CompactionPrompt {

    /** {@code ConversationCompactor} 替换对话文本的占位符。 */
    public static final String MESSAGES_PLACEHOLDER = "{messages}";

    /** 摘要提示词。 */
    public static final String SUMMARY = """
            你在为一段即将被替换的对话写交接摘要。摘要会取代下面的全部历史，之后的工作只能依靠它继续，所以要完整、具体、可执行。

            用对话所使用的语言，按下面五个部分写；某部分没有内容就写"无"：

            ## 目标
            用户要完成的事，以及用户明确提出的要求和约束。

            ## 关键决定
            已经做出的决定和理由，以及被否决的方案。

            ## 产物
            已创建、修改或读取的文件、数据与外部资源，写清路径或名称，以及改动要点。

            ## 未完成事项
            还没完成的工作、遇到的问题、尚未得到的答复。

            ## 下一步
            接下来要做的具体动作，按顺序列出。

            只输出摘要本身，不寒暄，不复述这段说明。

            <对话>
            {messages}
            </对话>
            """;

    private CompactionPrompt() {}
}
