package cn.refinex.meridian.engine.agentscope.middleware;

import cn.refinex.meridian.engine.agentscope.assembly.CompactionPrompt;
import cn.refinex.meridian.engine.agentscope.model.CompactionWatch;
import cn.refinex.meridian.engine.agentscope.model.RoutedModel;
import cn.refinex.meridian.engine.agentscope.model.TokenEstimator;
import cn.refinex.meridian.engine.spi.error.CompactionFailedException;
import cn.refinex.meridian.engine.spi.port.LeaseRegistry;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 压缩守护的外层（{@code onReasoning}，order {@value #ORDER}）：夹住 harness 的 {@code CompactionMiddleware}（order 1），
 * 内层是 {@link Inner}（order {@value Inner#ORDER}），见架构设计「平台中间件」。
 *
 * <p>为什么要守护：2.0.4 的压缩器直接调用 {@code model.stream(...)}，不经过 {@code onModelCall}；它吞掉模型异常，返回
 * {@code (Summarization failed: …)} 作为摘要，{@code CompactionMiddleware} 在调用下游之前就把这段文本写进了智能体状态。
 *
 * <ul>
 *   <li>外层：每次推理前把 {@link CompactionWatch} 放进 Reactor Context（压缩前的上下文副本、已有摘要的 ID）；按与
 *       {@code CompactionMiddleware} 相同的阈值算法判断本次会压缩时，先预检租约、预算与限流（只检查、不占用），不满足就抛平台异常，
 *       压缩不会开始。
 *   <li>内层：看到新出现的 {@code __compaction_summary__} 消息时检查摘要；摘要是失败文本时把智能体状态恢复为压缩前的副本并抛
 *       {@link CompactionFailedException}（原因取 {@link RoutedModel} 记在观察记录上的平台异常），否则发出
 *       {@value #CONTEXT_COMPACTED_EVENT} 自定义事件，事件映射把它翻译为 {@code agent.context_compacted}。内层把观察记录从推理调用的
 *       上下文中删除，{@code RoutedModel} 据此区分压缩调用与推理调用。
 * </ul>
 *
 * <p>阈值判断用 harness 的 {@code TokenCounterUtil} 计算未裁剪的会话：压缩器先裁剪再计算，裁剪只会减少 token，所以这里判断"不会压缩"
 * 时压缩器一定不会压缩，预检不会漏掉。
 *
 * <p>线程安全：无可变状态，所有智能体实例共享；每次推理的状态都在 Reactor Context 中。
 */
public final class CompactionGuardMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(CompactionGuardMiddleware.class);

    /** 外层的顺序：在平台中间件（最小的 {@code ToolSpanMiddleware} 为 50）之内、harness 自带的中间件（1）之外。 */
    public static final int ORDER = 20;

    /** 内层发出的压缩完成事件名，{@code TurnEventMapper} 按它映射为 {@code agent.context_compacted}。 */
    public static final String CONTEXT_COMPACTED_EVENT = "meridian.context_compacted";

    /** 事件值中压缩前的会话消息数。 */
    public static final String MESSAGES_BEFORE = "messages_before";

    /** 事件值中压缩后的会话消息数（含摘要）。 */
    public static final String MESSAGES_AFTER = "messages_after";

    /** 2.0.4 {@code ConversationCompactor} 在摘要失败时返回的文本前缀。 */
    static final String FAILED_SUMMARY_PREFIX = "(Summarization failed";

    /** 2.0.4 {@code ConversationCompactor} 在模型返回空内容时的摘要文本，同样不能替换历史。 */
    static final String UNAVAILABLE_SUMMARY = "(Summary unavailable)";

    /** 2.0.4 在不卸载原文时给摘要加的前缀（{@code buildSummaryMessage} 的 {@code filePath == null} 分支）。 */
    static final String SUMMARY_MESSAGE_PREFIX = "Here is a summary of the conversation to date:\n\n";

    private final LeaseRegistry leases;
    private final int reservedTokens;

    /**
     * 创建外层守护。
     *
     * @param leases 本节点持有的租约
     * @param reservedTokens 智能体配置的压缩预留 token，与装配给 {@code CompactionConfig} 的值相同
     */
    public CompactionGuardMiddleware(LeaseRegistry leases, int reservedTokens) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.reservedTokens = reservedTokens;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!(agent instanceof ReActAgent react)) {
            return next.apply(input);
        }
        return Flux.defer(() -> {
            List<Msg> conversation = conversation(input.messages());
            AgentState state = RuntimeContext.resolveAgentState(ctx, react);
            CompactionWatch watch = new CompactionWatch(
                    state == null ? null : state.contextMutable(), summaryIds(conversation), conversation.size());
            Mono<Void> precheck = compactionDue(react.getModel(), conversation)
                    ? precheck(ctx, react.getModel(), conversation)
                    : Mono.empty();
            return precheck.thenMany(Flux.defer(() -> next.apply(input)))
                    .contextWrite(context -> context.put(CompactionWatch.class, watch));
        });
    }

    /** 本次推理是否会触发压缩：与 2.0.4 {@code CompactionMiddleware} 的动态阈值算法相同（窗口减 reserved，过小时取窗口一半）。 */
    boolean compactionDue(Model model, List<Msg> conversation) {
        int window = model.getContextWindowSize();
        int trigger;
        if (window <= 0) {
            trigger = CompactionConfig.FALLBACK_TRIGGER_TOKENS;
        } else {
            trigger = window - reservedTokens > 0 ? window - reservedTokens : Math.max(1, window / 2);
        }
        return TokenCounterUtil.calculateToken(conversation) >= trigger;
    }

    /** 压缩前的预检：租约（同步）、预算与限流（经 RoutedModel，只检查不占用）。测试用的模型不是 RoutedModel 时只查租约。 */
    private Mono<Void> precheck(RuntimeContext ctx, Model model, List<Msg> conversation) {
        return Mono.defer(() -> {
            LeaseGuardMiddleware.verify(leases, ctx);
            if (!(model instanceof RoutedModel routed)) {
                return Mono.empty();
            }
            int input = TokenEstimator.messages(conversation) + TokenEstimator.text(CompactionPrompt.SUMMARY);
            log.debug("压缩前预检 estimatedInput={}", input);
            return routed.precheck(input);
        });
    }

    /** 去掉开头的系统消息后的会话，与 {@code CompactionMiddleware} 交给压缩器的范围相同。 */
    static List<Msg> conversation(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.getFirst().getRole() == MsgRole.SYSTEM ? messages.subList(1, messages.size()) : messages;
    }

    private static Set<String> summaryIds(List<Msg> conversation) {
        return conversation.stream()
                .filter(CompactionGuardMiddleware::isSummary)
                .map(Msg::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isSummary(Msg message) {
        return ConversationCompactor.SUMMARY_MSG_NAME.equals(message.getName());
    }

    /**
     * 压缩守护的内层（{@code onReasoning}，order {@value #ORDER}）：在 harness 的全部中间件之内，看到的是压缩之后的推理输入。
     *
     * <p>线程安全：无状态。
     */
    public static final class Inner implements MiddlewareBase {

        /** 内层的顺序：小于 harness 自带中间件的默认值 1，排在 {@code CompactionMiddleware} 之内。 */
        public static final int ORDER = 0;

        @Override
        public int order() {
            return ORDER;
        }

        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext ctx,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            return Flux.deferContextual(context -> {
                // 推理调用不在"压缩期间"：删掉观察记录，RoutedModel 据此把下游的调用记为推理
                Flux<AgentEvent> downstream =
                        Flux.defer(() -> next.apply(input)).contextWrite(c -> c.delete(CompactionWatch.class));
                CompactionWatch watch = context.getOrDefault(CompactionWatch.class, null);
                if (watch == null) {
                    return downstream;
                }
                List<Msg> conversation = conversation(input.messages());
                Msg summary = conversation.stream()
                        .filter(CompactionGuardMiddleware::isSummary)
                        .filter(message -> !watch.summaryIdsBefore().contains(message.getId()))
                        .findFirst()
                        .orElse(null);
                if (summary == null) {
                    return downstream;
                }
                if (failed(summaryText(summary))) {
                    restore(ctx, agent, watch);
                    Throwable cause = watch.modelFailure().orElse(null);
                    log.warn(
                            "压缩摘要失败，已恢复压缩前的上下文并结束本轮 cause={}",
                            cause == null ? "无" : cause.getClass().getSimpleName());
                    return Flux.error(new CompactionFailedException(cause));
                }
                log.info("上下文已压缩 messagesBefore={} messagesAfter={}", watch.messagesBefore(), conversation.size());
                Map<String, Object> value = new LinkedHashMap<>();
                value.put(MESSAGES_BEFORE, watch.messagesBefore());
                value.put(MESSAGES_AFTER, conversation.size());
                return Flux.concat(Mono.just(new CustomEvent(CONTEXT_COMPACTED_EVENT, value)), downstream);
            });
        }

        /** 摘要正文：去掉 2.0.4 加的固定前缀。 */
        static String summaryText(Msg summary) {
            StringBuilder text = new StringBuilder();
            for (ContentBlock block : summary.getContent()) {
                if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                    text.append(textBlock.getText());
                }
            }
            String content = text.toString();
            return content.startsWith(SUMMARY_MESSAGE_PREFIX)
                    ? content.substring(SUMMARY_MESSAGE_PREFIX.length())
                    : content;
        }

        static boolean failed(String summaryText) {
            String trimmed = summaryText.strip();
            return trimmed.startsWith(FAILED_SUMMARY_PREFIX) || trimmed.equals(UNAVAILABLE_SUMMARY);
        }

        /** 把智能体状态的上下文恢复为压缩前的副本：随后的错误会让 AgentScope 保存状态，失败的摘要不能被存下。 */
        private static void restore(RuntimeContext ctx, Agent agent, CompactionWatch watch) {
            AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
            if (state == null || watch.contextBefore().isEmpty()) {
                return;
            }
            List<Msg> context = state.contextMutable();
            context.clear();
            context.addAll(watch.contextBefore().get());
        }
    }
}
