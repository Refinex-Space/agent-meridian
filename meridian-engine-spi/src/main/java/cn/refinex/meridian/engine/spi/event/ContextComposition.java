package cn.refinex.meridian.engine.spi.event;

import java.util.Objects;

/**
 * 一次模型请求的上下文构成摘要：模型这一次实际看到的内容按来源分成五部分，各自的条数与 token 估算，放在
 * {@code span.model_request_start} 中供控制台展示，见架构设计「上下文管理」。
 *
 * <p>只有统计数字，不含任何内容正文。token 是平台按字符数的估算（宁可多算），不是供应商的计数；命中提示词缓存的 token
 * 要等响应结束才知道，在 {@code span.model_request_end} 的 {@code usage.cached_tokens} 中给出。
 *
 * @param systemPrompt 系统提示词（不含技能清单）；条数为系统消息数；必填
 * @param skills 技能清单（harness 追加在系统提示词末尾的 {@code <available_skills>}）；条数为列出的技能数；必填
 * @param history 历史消息：用户与助手的消息，含压缩摘要；必填
 * @param toolResults 工具结果消息；必填
 * @param injected 平台注入的内容：steer 消息、前置检索结果、恢复说明；必填
 */
public record ContextComposition(Part systemPrompt, Part skills, Part history, Part toolResults, Part injected) {

    /**
     * 创建构成摘要。
     *
     * @throws NullPointerException 缺少任一部分时
     */
    public ContextComposition {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(skills, "skills");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(toolResults, "toolResults");
        Objects.requireNonNull(injected, "injected");
    }

    /**
     * 一部分内容的统计。
     *
     * @param count 条数，不小于 0
     * @param tokens 估算的 token 数，不小于 0
     */
    public record Part(int count, int tokens) {

        /** 空的部分。 */
        public static final Part EMPTY = new Part(0, 0);

        /**
         * 创建统计。
         *
         * @throws IllegalArgumentException 任一项为负时
         */
        public Part {
            if (count < 0 || tokens < 0) {
                throw new IllegalArgumentException("条数与 token 数不能为负");
            }
        }

        /**
         * 加上一条内容。
         *
         * @param itemTokens 这条内容的 token 估算
         * @return 新的统计
         */
        public Part plus(int itemTokens) {
            return new Part(count + 1, tokens + itemTokens);
        }
    }
}
