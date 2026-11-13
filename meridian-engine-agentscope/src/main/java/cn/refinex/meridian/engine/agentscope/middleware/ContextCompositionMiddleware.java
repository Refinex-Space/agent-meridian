package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.engine.agentscope.assembly.PlatformContextMessage;
import cn.refinex.meridian.engine.agentscope.model.TokenEstimator;
import cn.refinex.meridian.engine.spi.event.ContextComposition;
import cn.refinex.meridian.engine.spi.event.ContextComposition.Part;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.harness.agent.skill.runtime.SkillPromptBuilder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 上下文构成（{@code onModelCall}）：统计这一次模型请求实际发出的消息，按来源分为系统提示词、技能清单、历史消息、工具结果、
 * 平台注入内容五部分，把 {@link ContextComposition} 挂在 2.0.4 的 {@code ModelCallStartEvent} 的元数据上，事件映射据此填进
 * {@code span.model_request_start}，见架构设计「上下文管理」。
 *
 * <p>{@code onModelCall} 看到的是所有推理中间件处理之后、真正交给模型的消息，所以统计的就是"模型这一轮实际看到了什么"。
 * 只统计条数与 token 估算（{@link TokenEstimator}），不保留任何正文。压缩器的内部调用不经过 {@code onModelCall}，不统计。
 *
 * <p>分类：系统消息中 harness 追加的 {@code <available_skills>} 块算技能清单，其余算系统提示词；带平台注入标记
 * （{@link SteerMiddleware#METADATA_KEY}、{@link RecoveryNoteMiddleware#METADATA_KEY}、平台上下文消息的
 * {@link PlatformContextMessage#METADATA_KEY}、前置检索的 {@link KnowledgePrefetchMiddleware#METADATA_KEY}）的消息算注入内容；工具消息与
 * 带工具结果的消息算工具结果；
 * 其余（用户、助手消息与压缩摘要）算历史。
 *
 * <p>线程安全：无状态。
 */
public final class ContextCompositionMiddleware implements MiddlewareBase {

    /** {@code ModelCallStartEvent} 元数据中构成摘要的键。 */
    public static final String METADATA_KEY = "meridian_context_composition";

    /** 顺序：只读取模型调用的入参，不改动，放在平台中间件之内即可。 */
    public static final int ORDER = 10;

    /** 平台注入消息的元数据标记。 */
    static final Set<String> INJECTION_MARKERS = Set.of(
            SteerMiddleware.METADATA_KEY,
            RecoveryNoteMiddleware.METADATA_KEY,
            PlatformContextMessage.METADATA_KEY,
            KnowledgePrefetchMiddleware.METADATA_KEY);

    private static final String SKILLS_OPEN = "<available_skills>";
    private static final String SKILLS_CLOSE = "</available_skills>";
    private static final String SKILL_ENTRY = "<skill>";

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, RuntimeContext ctx, ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            ContextComposition composition = compose(input.messages());
            return next.apply(input).doOnNext(event -> {
                if (event instanceof ModelCallStartEvent start) {
                    start.withMetadataEntry(METADATA_KEY, composition);
                }
            });
        });
    }

    /**
     * 统计一组消息的构成。
     *
     * @param messages 交给模型的消息
     * @return 构成摘要
     */
    static ContextComposition compose(List<Msg> messages) {
        Part system = Part.EMPTY;
        Part skills = Part.EMPTY;
        Part history = Part.EMPTY;
        Part toolResults = Part.EMPTY;
        Part injected = Part.EMPTY;
        for (Msg message : messages == null ? List.<Msg>of() : messages) {
            int tokens = TokenEstimator.message(message);
            if (message.getRole() == MsgRole.SYSTEM) {
                String text = text(message);
                SkillBlock block = SkillBlock.find(text);
                if (block != null) {
                    int skillTokens = TokenEstimator.text(block.text());
                    skills = new Part(skills.count() + block.count(), skills.tokens() + skillTokens);
                    tokens = Math.max(0, tokens - skillTokens);
                }
                system = system.plus(tokens);
            } else if (isInjected(message)) {
                injected = injected.plus(tokens);
            } else if (message.getRole() == MsgRole.TOOL || hasToolResult(message)) {
                toolResults = toolResults.plus(tokens);
            } else {
                history = history.plus(tokens);
            }
        }
        return new ContextComposition(system, skills, history, toolResults, injected);
    }

    private static boolean isInjected(Msg message) {
        Map<String, Object> metadata = message.getMetadata();
        return metadata != null && INJECTION_MARKERS.stream().anyMatch(metadata::containsKey);
    }

    private static boolean hasToolResult(Msg message) {
        return message.getContent() != null
                && message.getContent().stream().anyMatch(ToolResultBlock.class::isInstance);
    }

    private static String text(Msg message) {
        StringBuilder text = new StringBuilder();
        if (message.getContent() != null) {
            for (ContentBlock block : message.getContent()) {
                if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                    text.append(textBlock.getText());
                }
            }
        }
        return text.toString();
    }

    /**
     * 系统提示词中的技能清单块：从 2.0.4 {@code SkillPromptBuilder} 的默认标题（没有时从 {@code <available_skills>}）起，到
     * {@code </available_skills>} 为止，紧随其后的代码执行说明也算技能清单。
     */
    private record SkillBlock(String text, int count) {

        static SkillBlock find(String systemText) {
            int open = systemText.indexOf(SKILLS_OPEN);
            int close = systemText.indexOf(SKILLS_CLOSE);
            if (open < 0 || close < open) {
                return null;
            }
            String header = SkillPromptBuilder.DEFAULT_HEADER.strip();
            int headerStart = systemText.lastIndexOf(header.substring(0, header.indexOf('\n')), open);
            int start = headerStart >= 0 ? headerStart : open;
            int end = close + SKILLS_CLOSE.length();
            if (systemText.startsWith(SkillPromptBuilder.DEFAULT_CODE_EXECUTION_INSTRUCTION, end)) {
                end += SkillPromptBuilder.DEFAULT_CODE_EXECUTION_INSTRUCTION.length();
            }
            String block = systemText.substring(start, end);
            int count = 0;
            for (int i = systemText.indexOf(SKILL_ENTRY, open);
                    i >= 0 && i < close;
                    i = systemText.indexOf(SKILL_ENTRY, i + 1)) {
                count++;
            }
            return new SkillBlock(block, count);
        }
    }
}
