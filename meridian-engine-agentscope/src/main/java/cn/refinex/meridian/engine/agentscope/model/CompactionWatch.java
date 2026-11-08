package cn.refinex.meridian.engine.agentscope.model;

import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 一次推理前后压缩的观察记录，由 {@code CompactionGuardMiddleware} 的外层放进 Reactor Context，内层在
 * {@code CompactionMiddleware} 之内读取，见架构设计「平台中间件」。
 *
 * <p>它同时是"压缩期间"的标记：外层的 {@code contextWrite} 覆盖压缩器的内部模型调用，内层把它从推理调用的上下文中删除，
 * 所以 {@link RoutedModel} 看到它时，本次调用就是压缩器的摘要调用（用量记录的 {@code purpose=compaction}），调用失败时把
 * 平台异常记在这里，内层据此决定本轮如何结束（压缩器会吞掉模型异常，只留下失败文本）。
 *
 * <p>线程安全：除失败原因外不可变；失败原因为 volatile，压缩器的调用写、内层在其后读。
 *
 * @see RoutedModel
 */
public final class CompactionWatch {

    private final List<Msg> contextBefore;
    private final Set<String> summaryIdsBefore;
    private final int messagesBefore;
    private volatile Throwable modelFailure;

    /**
     * 创建观察记录。
     *
     * @param contextBefore 压缩前智能体状态中的上下文副本，压缩失败时用它恢复；可为空，表示没有状态
     * @param summaryIdsBefore 压缩前已有的摘要消息 ID，用来识别"新出现的"摘要
     * @param messagesBefore 压缩前推理输入中的会话消息数（不含系统消息）
     */
    public CompactionWatch(List<Msg> contextBefore, Set<String> summaryIdsBefore, int messagesBefore) {
        this.contextBefore = contextBefore == null ? null : List.copyOf(contextBefore);
        this.summaryIdsBefore = Set.copyOf(summaryIdsBefore);
        this.messagesBefore = messagesBefore;
    }

    /**
     * 压缩前的上下文副本。
     *
     * @return 副本；没有状态时为空
     */
    public Optional<List<Msg>> contextBefore() {
        return Optional.ofNullable(contextBefore);
    }

    /**
     * 压缩前已有的摘要消息 ID。
     *
     * @return 不可变集合
     */
    public Set<String> summaryIdsBefore() {
        return summaryIdsBefore;
    }

    /**
     * 压缩前的会话消息数。
     *
     * @return 消息数
     */
    public int messagesBefore() {
        return messagesBefore;
    }

    /**
     * 记录压缩器内部模型调用的失败（{@link RoutedModel} 调用）。
     *
     * @param failure 已翻译为平台异常的错误
     */
    void recordFailure(Throwable failure) {
        this.modelFailure = failure;
    }

    /**
     * 压缩器内部模型调用的失败。
     *
     * @return 失败原因；调用没有失败或没有经过 {@link RoutedModel} 时为空
     */
    public Optional<Throwable> modelFailure() {
        return Optional.ofNullable(modelFailure);
    }
}
