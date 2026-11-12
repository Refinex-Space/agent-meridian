package cn.refinex.meridian.runtime.modelrouting.app;

import cn.refinex.meridian.common.budget.BudgetLevel;
import cn.refinex.meridian.engine.spi.event.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 模型调用的指标（架构设计「可观测性」）。Micrometer 名用点分隔，导出到 Prometheus 时转为下划线并按类型加后缀：
 *
 * <ul>
 *   <li>{@code meridian.model.tokens}（counter，{@code model}、{@code type}）→ {@code meridian_model_tokens_total}：结算的 token，
 *       {@code type} 为 {@code input}、{@code output}、{@code cache_read}、{@code cache_write}、{@code reasoning}。
 *   <li>{@code meridian.model.first.token}（timer，{@code model}）→ {@code meridian_model_first_token_seconds}：从发出请求到收到第一个
 *       带内容的增量。
 *   <li>{@code meridian.budget.rejections}（counter，{@code level}）→ {@code meridian_budget_rejections_total}：预扣或压缩前预检因
 *       某级预算不足被拒绝。
 * </ul>
 *
 * <p>{@code model} 标签是平台模型 ID（{@code mdl_…}），取值有限。线程安全：Micrometer 的计量器线程安全。
 */
@Component
public class ModelMetrics {

    private final MeterRegistry registry;

    /**
     * 创建指标。
     *
     * @param registry 指标注册表
     */
    public ModelMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次调用结算的 token。
     *
     * @param modelId 模型 ID
     * @param usage 用量
     */
    public void tokens(String modelId, TokenUsage usage) {
        count(modelId, "input", usage.inputTokens());
        count(modelId, "output", usage.outputTokens());
        count(modelId, "cache_read", usage.cachedTokens());
        count(modelId, "cache_write", usage.cacheCreationTokens());
        count(modelId, "reasoning", usage.reasoningTokens());
    }

    /**
     * 记录首 token 时间。
     *
     * @param modelId 模型 ID
     * @param latency 从发出请求到第一个带内容的增量
     */
    public void firstToken(String modelId, Duration latency) {
        Timer.builder("meridian.model.first.token")
                .description("从发出模型请求到收到第一个带内容的增量的时长")
                .tag("model", modelId)
                .publishPercentileHistogram()
                .register(registry)
                .record(latency);
    }

    /**
     * 记录一次预算拒绝。
     *
     * @param level 不足的级别
     */
    public void budgetRejected(BudgetLevel level) {
        Counter.builder("meridian.budget.rejections")
                .description("模型调用因某级预算不足被拒绝的次数")
                .tag("level", level.value())
                .register(registry)
                .increment();
    }

    private void count(String modelId, String type, int tokens) {
        Counter.builder("meridian.model.tokens")
                .description("模型调用结算的 token 数")
                .tag("model", modelId)
                .tag("type", type)
                .register(registry)
                .increment(tokens);
    }
}
