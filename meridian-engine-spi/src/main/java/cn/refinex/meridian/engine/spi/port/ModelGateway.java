package cn.refinex.meridian.engine.spi.port;

import cn.refinex.meridian.engine.spi.event.TokenUsage;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * 模型调用的平台侧服务：解析供应商账号与凭据、限流、预算预扣与结算、用量记录，供 {@code RoutedModel} 在每次
 * {@code doStream} 中依次调用，由 runtime 的 {@code modelrouting} 实现，见架构设计「端口」「模型调用」「计量与预算」。
 *
 * <p>租户、项目、会话与预算句柄从 Reactor Context 中的 {@code PlatformContext} 取，压缩器内部的模型调用也在
 * 同一条订阅链上。预算与限流异常不重试、不切换备用模型。
 */
public interface ModelGateway {

    /**
     * 解析模型的供应商账号、端点与凭据。结果可以按"供应商账号加模型"缓存。
     *
     * @param modelId 模型 ID（{@code mdl_…}）
     * @return 路由信息；模型不存在或未授权给该项目时以 {@code NotFoundException} 失败
     */
    Mono<ModelRoute> resolve(String modelId);

    /**
     * 按"供应商账号加模型"的 RPM 与 TPM 用令牌桶等待，最长 30 秒。
     *
     * @param route 路由信息
     * @param estimatedTokens 估算的本次 token 数（输入加最大输出），不小于 0
     * @return 拿到配额后完成；等待超时以 {@code RateLimitTimeoutException} 失败（可重试）
     */
    Mono<Void> awaitRateLimit(ModelRoute route, int estimatedTokens);

    /**
     * 按"估算输入 token 加 {@code max_tokens}"预扣预算。
     *
     * @param route 路由信息
     * @param estimatedTokens 估算的本次 token 数，不小于 0
     * @return 预扣凭据；预算不足时以 {@code BudgetExceededException} 失败，本轮以 {@code budget_exceeded} 结束
     */
    Mono<BudgetReservation> reserve(ModelRoute route, int estimatedTokens);

    /**
     * 在流结束时按实际用量结算预扣，并写入用量记录（经 outbox 投递到 control-plane）。
     *
     * @param reservation 预扣凭据
     * @param usage 实际用量
     * @return 结算并记录后完成
     */
    Mono<Void> settle(BudgetReservation reservation, TokenUsage usage);

    /**
     * 调用在产生用量之前失败时，释放预扣。
     *
     * @param reservation 预扣凭据
     * @return 释放后完成
     */
    Mono<Void> release(BudgetReservation reservation);

    /**
     * 模型的路由信息。
     *
     * @param modelId 模型 ID；必填
     * @param providerAccountId 供应商账号 ID（{@code prv_…}）；必填，限流与底层客户端缓存的键
     * @param provider 供应商类型，决定用哪个 AgentScope 模型类构造底层客户端；必填
     * @param baseUrl 供应商端点；可为空，表示用该供应商的默认端点
     * @param upstreamModel 供应商侧的模型名，例如 {@code qwen-plus}；必填
     * @param apiKey 凭据明文；可为空，表示该供应商不需要凭据（例如本地 Ollama）
     * @param contextWindow 模型上下文窗口，单位 token，大于 0；压缩阈值据此计算
     */
    record ModelRoute(
            String modelId,
            String providerAccountId,
            Provider provider,
            String baseUrl,
            String upstreamModel,
            SecretValue apiKey,
            int contextWindow) {

        /**
         * 创建路由信息。
         *
         * @throws NullPointerException 缺少必填字段时
         * @throws IllegalArgumentException 上下文窗口不大于 0 时
         */
        public ModelRoute {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(providerAccountId, "providerAccountId");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(upstreamModel, "upstreamModel");
            if (contextWindow < 1) {
                throw new IllegalArgumentException("contextWindow 必须大于 0：" + contextWindow);
            }
        }
    }

    /** 供应商类型，与架构设计「模型调用」列出的 AgentScope 模型类对应。 */
    enum Provider {
        /** OpenAI 兼容接口（含 DashScope 兼容模式、私有化部署的兼容服务）。 */
        OPENAI_COMPATIBLE,
        /** Anthropic。 */
        ANTHROPIC,
        /** Gemini。 */
        GEMINI,
        /** DashScope 原生接口。 */
        DASHSCOPE,
        /** Ollama。 */
        OLLAMA
    }

    /**
     * 一次预算预扣的凭据。
     *
     * @param reservationId 预扣 ID，结算与释放时使用；必填
     * @param reservedTokens 预扣的 token 数，不小于 0
     */
    record BudgetReservation(String reservationId, long reservedTokens) {

        /**
         * 创建预扣凭据。
         *
         * @throws NullPointerException 缺少预扣 ID 时
         * @throws IllegalArgumentException 预扣数为负时
         */
        public BudgetReservation {
            Objects.requireNonNull(reservationId, "reservationId");
            if (reservedTokens < 0) {
                throw new IllegalArgumentException("reservedTokens 不能为负：" + reservedTokens);
            }
        }
    }
}
